package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CamelYamlExamplesTest {
    private static final String ROOT = "[default]CamelExamples/EIP/";
    private static final String UNIFIED = "[default]CamelExamples/Unified/Latest";

    private static CamelRouteResource example(String name) throws Exception {
        return new CamelRouteResource(Files.readString(Path.of(System.getProperty("camel.examplesDirectory"), name)));
    }

    @Test void yamlFilesValidateWithoutRunningScriptsOrConnectingToABroker() throws Exception {
        CamelRouteRuntime.validate(example("multi-source-normalization.yaml"));
        CamelRouteRuntime.validate(example("eip-to-tags.yaml"));
        CamelRouteRuntime.validate(example("control-bus.yaml"));
        CamelRouteRuntime.validate(example("work-orders.yaml"));
        CamelRouteRuntime.validate(example("work-orders-mqtt.yaml"));
        CamelRouteRuntime.validate(example("idempotent-consumer.yaml"));
        CamelRouteRuntime.validate(example("mqtt-to-tags/route.yaml"));
        CamelRouteRuntime.validate(example("tags-to-mqtt/route.yaml"));
        CamelRouteRuntime.validate(example("json-file-to-tags/route.yaml"));
    }

    @Test void expressionConstantsUseStringsOrObjectsAsRequiredByTheCamelYamlSchema() throws Exception {
        var loader = new org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build());
        for (String file : List.of("multi-source-normalization.yaml", "eip-to-tags.yaml",
            "control-bus.yaml", "work-orders.yaml", "work-orders-mqtt.yaml",
            "idempotent-consumer.yaml", "mqtt-to-tags/route.yaml",
            "tags-to-mqtt/route.yaml", "json-file-to-tags/route.yaml")) {
            assertConstantTypes(loader.loadFromString(example(file).yaml()), file);
        }
    }

    private static void assertConstantTypes(Object node, String file) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("constant".equals(key)) assertTrue(value instanceof String || value instanceof Map,
                    () -> file + ": constant expressions must be strings or expression objects, not " + value);
                assertConstantTypes(value, file);
            });
        } else if (node instanceof List<?> list) list.forEach(value -> assertConstantTypes(value, file));
    }

    @Test void workOrderRoutesStartWithOneDynamicCompletionFlow() throws Exception {
        var services = new IgnitionServices() {
            public String requireTagPath(String path) { return path; }
            public AutoCloseable subscribe(String path, Consumer<TagValue> listener) {
                assertTrue(!path.contains("/Machines/") || path.endsWith("/Machines/*/Complete"),
                    "Machine completion uses one native wildcard source");
                return () -> { };
            }
            public void write(String path, Object value) { }
            public Object invoke(String project, String function, Object body, Map<String, Object> headers) { return null; }
        };
        var components = new IgnitionComponents(services);
        try (var runtime = new CamelRouteRuntime("dynamic-wo-model", example("work-orders.yaml"), context -> {
            components.install(context);
            var queue = new org.apache.camel.component.seda.SedaComponent();
            queue.setQueueSize(1000);
            queue.setConcurrentConsumers(2);
            context.addComponent("some-queue", queue);
            for (String name : List.of("wo-audit", "wo-pending")) {
                var endpoint = context.getEndpoint("some-queue:" + name + "?concurrentConsumers=1",
                    org.apache.camel.component.seda.SedaEndpoint.class);
                assertEquals(1000, endpoint.getSize(), "Capacity must come from the named component");
                assertEquals(1, endpoint.getConcurrentConsumers(), "Consumer ordering overrides the component default");
            }
            new IgnitionPlatformHttpEngine().install(context);
            context.getRegistry().bind("wo-service", (org.apache.camel.Processor) exchange -> exchange.getMessage().setBody(List.of()));
            context.getRegistry().bind("wo-batch", new org.apache.camel.processor.aggregate.GroupedBodyAggregationStrategy());
        })) {
            assertTrue(runtime.isStarted());
        }
    }

    @Test void controlBusExampleWritesSuspendsAndResumesTheTimer() throws Exception {
        var heartbeats = new LinkedBlockingQueue<String>();
        var states = new LinkedBlockingQueue<String>();
        var counts = new LinkedBlockingQueue<Long>();
        var command = new AtomicReference<Consumer<IgnitionServices.TagValue>>();
        var services = new IgnitionServices() {
            public String requireTagPath(String path) { assertTrue(path.startsWith("[default]CamelExamples/ControlBus/")); return path; }
            public AutoCloseable subscribe(String path, Consumer<TagValue> listener) { command.set(listener); return () -> { }; }
            public void write(String path, Object value) {
                if (path.endsWith("/Heartbeat")) heartbeats.add(value.toString());
                if (path.endsWith("/TickCount")) counts.add(((Number) value).longValue());
                if (path.endsWith("/RouteState")) states.add(value.toString());
            }
            public Object invoke(String project, String function, Object body, Map<String, Object> headers) { throw new AssertionError("Control Bus routing must stay YAML-only"); }
        };
        var components = new IgnitionComponents(services);
        try (var runtime = new CamelRouteRuntime("yaml-controlbus-example", example("control-bus.yaml"), components::install)) {
            assertNotNull(heartbeats.poll(5, TimeUnit.SECONDS));
            assertTrue(counts.poll(5, TimeUnit.SECONDS) > 0);
            command.get().accept(new IgnitionServices.TagValue("suspend", "Good", true, 1, false));
            awaitState(states, "Suspended");
            heartbeats.clear(); counts.clear();
            assertNull(heartbeats.poll(2500, TimeUnit.MILLISECONDS), "Suspended timer must stop writing tags");
            assertTrue(counts.isEmpty());
            command.get().accept(new IgnitionServices.TagValue("resume", "Good", true, 2, false));
            awaitState(states, "Started");
            assertNotNull(heartbeats.poll(5, TimeUnit.SECONDS));
            assertTrue(counts.poll(5, TimeUnit.SECONDS) > 0);
        }
    }

    private static void awaitState(LinkedBlockingQueue<String> states, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (expected.equals(states.poll(100, TimeUnit.MILLISECONDS))) return;
        }
        fail("Control Bus did not report " + expected);
    }

    @Test void eipExampleSplitsFiltersChoosesAndMulticastsWithoutAProcessingScript() throws Exception {
        var writes = new ArrayList<Write>();
        var services = new IgnitionServices() {
            public String requireTagPath(String path) { assertTrue(path.startsWith(ROOT)); return path; }
            public AutoCloseable subscribe(String path, Consumer<TagValue> listener) { return () -> { }; }
            public void write(String path, Object value) { writes.add(new Write(path.substring(ROOT.length()), value)); }
            public Object invoke(String project, String function, Object body, Map<String, Object> headers) { throw new AssertionError("The EIP data path must stay YAML-only"); }
        };
        var components = new IgnitionComponents(services);
        var http = new IgnitionPlatformHttpEngine();
        try (var runtime = new CamelRouteRuntime("yaml-eip-example", example("eip-to-tags.yaml"), context -> {
            components.install(context); http.install(context);
        })) {
            runtime.request("direct:classify-readings", List.of(
                Map.of("sensor", "tank-1", "value", 42, "enabled", true),
                Map.of("sensor", "tank-2", "value", 88, "enabled", true),
                Map.of("sensor", "disabled", "value", 999, "enabled", false)));
            assertEquals(List.of(
                new Write("Normal", "tank-1 = 42 (NORMAL)"),
                new Write("LastRouted", "tank-1 = 42 (NORMAL)"),
                new Write("AuditLast", "tank-1 = 42 (NORMAL)"),
                new Write("High", "tank-2 = 88 (HIGH)"),
                new Write("LastRouted", "tank-2 = 88 (HIGH)"),
                new Write("AuditLast", "tank-2 = 88 (HIGH)")), writes);

            writes.clear();
            runtime.request("direct:classify-readings", List.of(Map.of("sensor", "ignored", "value", 999, "enabled", false)));
            assertTrue(writes.isEmpty());

            runtime.request("direct:classify-readings", List.of(Map.of("sensor", "threshold", "value", 75, "enabled", true)));
            assertEquals(List.of(new Write("High", "threshold = 75 (HIGH)"),
                new Write("LastRouted", "threshold = 75 (HIGH)"), new Write("AuditLast", "threshold = 75 (HIGH)")), writes);
        }
    }

    @Test void multiSourceInputsConvergeOnOneServiceRoute() throws Exception {
        var writes = new LinkedBlockingQueue<Object>();
        var tagInput = new AtomicReference<Consumer<IgnitionServices.TagValue>>();
        var services = new IgnitionServices() {
            public String requireTagPath(String path) {
                assertTrue(path.startsWith("[default]CamelExamples/Unified/"));
                return path;
            }
            public AutoCloseable subscribe(String path, Consumer<TagValue> listener) {
                if (path.endsWith("/RawTemperature")) tagInput.set(listener);
                return () -> { };
            }
            public void write(String path, Object value) { writes.offer(value); }
            public Object invoke(String project, String function, Object body,
                Map<String, Object> headers) {
                assertEquals("CamelExamples", project);
                assertEquals("unified_readings.normalize", function);
                switch (headers.get("readingSource").toString()) {
                    case "http" -> assertEquals("Press01",
                        assertInstanceOf(Map.class, body).get("machine"));
                    case "mqtt" -> assertEquals(194,
                        assertInstanceOf(Map.class, body).get("temperatureF"));
                    case "ignition-tag" -> assertEquals(45, body);
                    default -> fail("Unexpected reading source");
                }
                return Map.of("machine", "Press01", "temperatureC", 30.0,
                    "status", "NORMAL", "source", headers.get("readingSource"));
            }
        };
        var components = new IgnitionComponents(services);
        var http = new IgnitionPlatformHttpEngine();
        try (var runtime = new CamelRouteRuntime("multi-source-example",
            example("multi-source-normalization.yaml"), context -> {
                components.install(context);
                http.install(context);
                context.addComponent("example-mqtt",
                    new org.apache.camel.component.seda.SedaComponent());
            })) {
            var httpResult = http.request("/camel-example-unified-readings/readings",
                Map.of("machine", "Press01", "temperatureC", 30), Map.of()).body();
            assertEquals(httpResult, writes.poll(5, TimeUnit.SECONDS));
            assertEquals("http",
                new Gson().fromJson(httpResult.toString(), Map.class).get("source"));

            tagInput.get().accept(new IgnitionServices.TagValue(
                45, "Good", true, 1, false));
            var tag = writes.poll(5, TimeUnit.SECONDS);
            assertNotNull(tag);
            assertEquals("ignition-tag",
                new Gson().fromJson(tag.toString(), Map.class).get("source"));

            runtime.send("example-mqtt:camel-demo/readings",
                "{\"device\":\"Press02\",\"temperatureF\":194}", Map.of());
            var mqtt = writes.poll(5, TimeUnit.SECONDS);
            assertNotNull(mqtt);
            assertEquals("mqtt",
                new Gson().fromJson(mqtt.toString(), Map.class).get("source"));
        }
    }

    private record Write(String tag, Object value) { }
}
