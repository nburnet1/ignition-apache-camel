package com.noahburnette.ignition.apachecamel.camel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jolokia.json.JSONObject;
import org.jolokia.json.parser.JSONParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CamelHawtioTest {
    public interface MutableProbeMBean {
        String getValue();
        void setValue(String value);
        String inspect();
    }

    public static final class MutableProbe implements MutableProbeMBean {
        private String value = "unchanged";
        private int calls;
        @Override public String getValue() { return value; }
        @Override public void setValue(String value) { this.value = value; }
        @Override public String inspect() { calls++; return "not guaranteed read-only despite its name"; }
    }
    private static final String YAML = """
        - route:
            id: managed-route
            from:
              uri: direct:managed
              steps:
                - setBody:
                    constant: done
        """;

    private static JSONObject call(CamelHawtio bridge, String json) throws Exception {
        return (JSONObject) new JSONParser().parse(bridge.post(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String routeBean(CamelHawtio bridge) throws Exception {
        var response = call(bridge, "{\"type\":\"search\",\"mbean\":\"org.apache.camel:type=routes,*\"}");
        assertEquals(200L, response.get("status"), response.toString());
        var names = (List<?>) response.get("value");
        assertEquals(1, names.size(), response.toString());
        return names.get(0).toString();
    }

    private static JSONObject control(CamelHawtio bridge, String bean, String operation) throws Exception {
        var request = new JSONObject();
        request.putAll(Map.of("type", "exec", "mbean", bean, "operation", operation));
        return call(bridge, request.toJSONString());
    }

    @Test void discoversOnlyOwnedContextsAndRoutesAndCleansUp() throws Exception {
        try (var bridge = new CamelHawtio(false)) {
            assertEquals(200L, call(bridge, "{\"type\":\"version\"}").get("status"));
            try (var runtime = new CamelRouteRuntime("managed", new CamelRouteResource(YAML), bridge::install)) {
                assertTrue(runtime.isStarted());
                assertEquals("done", runtime.request("direct:managed", null));
                routeBean(bridge);
                var all = call(bridge, "{\"type\":\"search\",\"mbean\":\"*:*\"}");
                var names = (List<?>) all.get("value");
                assertEquals(2, names.size(), all.toString());
                assertTrue(names.stream().allMatch(name -> name.toString().startsWith("org.apache.camel:")));
                assertFalse(bridge.post("{\"type\":\"list\"}".getBytes(StandardCharsets.UTF_8)).contains("EndpointUri"));
                assertNotEquals(200L, call(bridge, "{\"type\":\"read\",\"mbean\":\"java.lang:type=Memory\"}").get("status"));
            }
            assertEquals(List.of(), call(bridge, "{\"type\":\"search\",\"mbean\":\"org.apache.camel:*\"}").get("value"));
            // Same identities can be reused after replacement without stale registrations.
            try (var replacement = new CamelRouteRuntime("managed", new CamelRouteResource(YAML), bridge::install)) {
                routeBean(bridge);
            }
        }
    }

    @Test void explicitRouteControlsAreReflectedByIgnitionStatistics() throws Exception {
        try (var bridge = new CamelHawtio(true);
             var runtime = new CamelRouteRuntime("controlled", new CamelRouteResource(YAML), bridge::install)) {
            String bean = routeBean(bridge);
            assertEquals(200L, control(bridge, bean, "stop").get("status"));
            assertEquals("STOPPED", runtime.statistics().snapshot().get(0).get("state"));
            assertEquals(200L, control(bridge, bean, "start()").get("status"));
            assertEquals("RUNNING", runtime.statistics().snapshot().get(0).get("state"));
            assertEquals("done", runtime.request("direct:managed", null));
            assertNotEquals(200L, control(bridge, bean, "dumpRouteAsXml").get("status"));
            var read = new JSONObject();
            read.putAll(Map.of("type", "read", "mbean", bean));
            var options = new JSONObject();
            options.putAll(Map.of("maxDepth", 99999, "ignoreErrors", true));
            read.put("config", options);
            var values = (JSONObject) call(bridge, read.toJSONString()).get("value");
            assertEquals("Started", values.get("State"));
            assertFalse(values.containsKey("EndpointUri"));
        }
    }

    @Test void controlsAreDeniedByDefaultAndMetadataDoesNotOfferMutations() throws Exception {
        try (var bridge = new CamelHawtio(false);
             var runtime = new CamelRouteRuntime("readonly", new CamelRouteResource(YAML), bridge::install)) {
            String bean = routeBean(bridge);
            assertNotEquals(200L, control(bridge, bean, "stop").get("status"));
            assertEquals("RUNNING", runtime.statistics().snapshot().get(0).get("state"));
            String metadata = bridge.post("{\"type\":\"list\"}".getBytes(StandardCharsets.UTF_8));
            assertFalse(metadata.contains("dumpRoutes"), metadata);
            assertFalse(metadata.contains("EndpointUri"), metadata);
            assertFalse(metadata.contains("sendBody"), metadata);
        }
    }

    @Test void rejectsOverridesRemoteTargetsWritesAndOversizedInput() throws Exception {
        try (var bridge = new CamelHawtio(true)) {
            for (String request : List.of(
                "{\"type\":\"version\",\"config\":{\"includeStackTrace\":true}}",
                "{\"type\":\"version\",\"target\":{\"url\":\"service:jmx:rmi:///forbidden\"}}",
                "{\"type\":\"write\"}",
                "{\"type\":\"exec\",\"arguments\":[\"untrusted\"]}")) {
                assertThrows(SecurityException.class, () -> call(bridge, request));
            }
            assertThrows(IllegalArgumentException.class, () -> bridge.post(new byte[CamelHawtio.MAX_REQUEST_BYTES + 1]));
            assertThrows(IllegalArgumentException.class, () -> call(bridge, "[]"));
        }
    }

    @Test void failedStartupLeavesNoManagedBeans() throws Exception {
        try (var bridge = new CamelHawtio(false)) {
            assertThrows(Exception.class, () -> new CamelRouteRuntime("broken", new CamelRouteResource(
                YAML.replace("direct:managed", "missing-component:managed")), bridge::install));
            assertEquals(List.of(), call(bridge, "{\"type\":\"search\",\"mbean\":\"org.apache.camel:*\"}").get("value"));
        }
    }

    @Test void separateBridgesDoNotDiscoverEachOtherOrThePlatform() throws Exception {
        var platform = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        var before = platform.queryNames(new javax.management.ObjectName("org.apache.camel:*"), null);
        try (var first = new CamelHawtio(true); var second = new CamelHawtio(false);
             var runtime = new CamelRouteRuntime("private", new CamelRouteResource(YAML), first::install)) {
            routeBean(first);
            assertEquals(List.of(), call(second, "{\"type\":\"search\",\"mbean\":\"*:*\"}").get("value"));
            assertEquals(before, platform.queryNames(new javax.management.ObjectName("org.apache.camel:*"), null));
            // Loading management dependencies never makes ordinary runtimes or save validation managed.
            try (var unmanaged = new CamelRouteRuntime("unmanaged", new CamelRouteResource(YAML))) {
                CamelRouteRuntime.validate(new CamelRouteResource(YAML));
                assertEquals(before, platform.queryNames(new javax.management.ObjectName("org.apache.camel:*"), null));
            }
        }
    }

    @Test void lifecycleFailuresDoNotExposeExceptionMessages() throws Exception {
        try (var bridge = new CamelHawtio(true);
             var runtime = new CamelRouteRuntime("failure", new CamelRouteResource(YAML
                 .replace("id: managed-route", "id: managed-route\n    autoStartup: false")
                 .replace("direct:managed", "failing:managed")), context -> {
                     bridge.install(context);
                     context.addComponent("failing", new org.apache.camel.support.DefaultComponent() {
                         @Override protected org.apache.camel.Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
                             return new org.apache.camel.support.DefaultEndpoint(uri, this) {
                                 @Override public org.apache.camel.Producer createProducer() { throw new UnsupportedOperationException(); }
                                 @Override public org.apache.camel.Consumer createConsumer(org.apache.camel.Processor processor) {
                                     return new org.apache.camel.support.DefaultConsumer(this, processor) {
                                         @Override protected void doStart() { throw new IllegalStateException("private-credential-must-not-escape"); }
                                     };
                                 }
                             };
                         }
                     });
                 })) {
            var response = control(bridge, routeBean(bridge), "start()");
            assertNotEquals(200L, response.get("status"));
            assertFalse(response.toJSONString().contains("private-credential-must-not-escape"));
            assertFalse(response.containsKey("stacktrace"));
            assertFalse(response.containsKey("error_value"));
        }
    }

    @Test void jvmReadonlyReadsLocalServersAndXmlButNeverAllowsMutations() throws Exception {
        var external = javax.management.MBeanServerFactory.createMBeanServer("hawtio-test");
        var name = new javax.management.ObjectName("hawtio-test:type=MutableProbe");
        var probe = new MutableProbe();
        external.registerMBean(probe, name);
        try {
            try (var bridge = new CamelHawtio(true, CamelHawtio.JVM_READONLY_SCOPE);
                 var runtime = new CamelRouteRuntime("inspection", new CamelRouteResource(YAML), bridge::install)) {
                assertFalse(bridge.controlEnabled(), "Read-only scope must override the control flag");
                var memory = call(bridge, "{\"type\":\"read\",\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"HeapMemoryUsage\"}");
                assertEquals(200L, memory.get("status"), memory.toString());
                var read = call(bridge, "{\"type\":\"read\",\"mbean\":\"hawtio-test:type=MutableProbe\",\"attribute\":\"Value\"}");
                assertEquals("unchanged", read.get("value"));
                assertThrows(SecurityException.class, () -> call(bridge,
                    "{\"type\":\"write\",\"mbean\":\"hawtio-test:type=MutableProbe\",\"attribute\":\"Value\",\"value\":\"changed\"}"));
                assertNotEquals(200L, control(bridge, name.toString(), "inspect()").get("status"));
                assertEquals(0, probe.calls);
                assertEquals("unchanged", probe.value);
                String route = routeBean(bridge);
                assertEquals(false, control(bridge, route, "isUpdateRouteEnabled()").get("value"));
                for (String operation : List.of("stop", "stop()", "start()", "reset()", "remove()", "updateRouteFromXml")) {
                    assertNotEquals(200L, control(bridge, route, operation).get("status"), operation);
                }
                assertEquals("RUNNING", runtime.statistics().snapshot().get(0).get("state"));
                var contexts = (List<?>) call(bridge, "{\"type\":\"search\",\"mbean\":\"org.apache.camel:type=context,*\"}").get("value");
                String context = contexts.stream().filter(value -> value.toString().contains("integration-inspection")).findFirst().orElseThrow().toString();
                var xml = control(bridge, context, "dumpRoutesAsXml()");
                assertEquals(200L, xml.get("status"), xml.toString());
                assertTrue(xml.get("value").toString().contains("managed-route"), xml.toString());
                var statistics = new JSONObject();
                statistics.putAll(Map.of("type", "exec", "mbean", context, "operation", "dumpRoutesStatsAsXml(boolean,boolean)"));
                var arguments = new org.jolokia.json.JSONArray();
                arguments.add(true); arguments.add(true);
                statistics.put("arguments", arguments);
                var stats = call(bridge, statistics.toJSONString());
                assertEquals(200L, stats.get("status"), stats.toString());
                assertTrue(stats.get("value").toString().contains("routeStat"));
                statistics.put("operation", "dumpRoutesAsXml(boolean,boolean)");
                assertNotEquals(200L, call(bridge, statistics.toJSONString()).get("status"), "Unreviewed overloads must be denied");
                var metadata = call(bridge, "{\"type\":\"list\",\"path\":\"hawtio-test\"}").toJSONString();
                assertFalse(metadata.contains("\"inspect\":"), metadata);
                assertTrue(metadata.contains("\"rw\":false") || metadata.contains("\"rw\": false"), metadata);
                assertTrue(metadata.contains("\"canInvoke\":false"), metadata);
                var routeMetadataRequest = new JSONObject();
                routeMetadataRequest.putAll(Map.of("type", "list", "path", "org.apache.camel/" + route.substring(route.indexOf(':') + 1)));
                var routeMetadata = (JSONObject) call(bridge, routeMetadataRequest.toJSONString()).get("value");
                var permissions = (JSONObject) routeMetadata.get("opByString");
                assertEquals(false, ((JSONObject) permissions.get("stop()")).get("canInvoke"));
                assertEquals(true, ((JSONObject) permissions.get("isUpdateRouteEnabled()")).get("canInvoke"));
            }
            assertTrue(external.isRegistered(name), "Bridge shutdown must never unregister Gateway-owned MBeans");
        } finally {
            external.unregisterMBean(name);
            javax.management.MBeanServerFactory.releaseMBeanServer(external);
        }
    }

    @Test void rejectsUnknownScopeRatherThanOpeningAccess() {
        assertThrows(IllegalArgumentException.class, () -> new CamelHawtio(true, "jvm-readwrite"));
    }
}
