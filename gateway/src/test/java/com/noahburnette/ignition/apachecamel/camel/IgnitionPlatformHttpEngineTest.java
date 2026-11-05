package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IgnitionPlatformHttpEngineTest {
    private static final String YAML = """
        - route:
            from:
              uri: ignition-gateway-http:/echo?httpMethodRestrict=POST
              steps:
                - setHeader:
                    name: CamelHttpResponseCode
                    constant: 202
        """;

    @Test
    void executesNativeConsumerAndUnregistersOnClose() throws Exception {
        var engine = new IgnitionPlatformHttpEngine();
        assertEquals(List.of(), engine.registeredPaths());
        var previous = Thread.currentThread().getContextClassLoader();
        try (var runtime = new CamelRouteRuntime("http", new CamelRouteResource(YAML), engine::install)) {
            assertEquals(List.of("/echo"), engine.registeredPaths());
            var payload = Map.of("integer", 9007199254740993L);
            var result = engine.request("/echo", payload, Map.of());
            assertEquals(202, result.status());
            assertSame(payload, result.body());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertEquals(404, assertThrows(IgnitionPlatformHttpEngine.HttpFailure.class,
                () -> engine.request("/missing", null, Map.of())).status());
        }
        assertEquals(List.of(), engine.registeredPaths());
        assertEquals(404, assertThrows(IgnitionPlatformHttpEngine.HttpFailure.class,
            () -> engine.request("/echo", null, Map.of())).status());
    }

    @Test
    void removedPlatformHttpAliasCannotStartAConsumer() {
        var engine = new IgnitionPlatformHttpEngine();
        assertThrows(Exception.class, () -> new CamelRouteRuntime("removed-alias",
            new CamelRouteResource(YAML.replace("ignition-gateway-http:", "platform-http:")), engine::install));
        assertEquals(404, assertThrows(IgnitionPlatformHttpEngine.HttpFailure.class,
            () -> engine.request("/echo", null, Map.of())).status());
    }

    @Test
    void rejectsUnsupportedOptionsAndDuplicatePathsWithoutAffectingOtherContexts() throws Exception {
        for (String uri : new String[]{"ignition-gateway-http:/echo?httpMethodRestrict=GET", "ignition-gateway-http:/nested//path",
            "ignition-gateway-http:/nested/../path", "ignition-gateway-http:/nested;param=x",
            "ignition-gateway-http:/echo?matchOnUriPrefix=true", "ignition-gateway-http:/echo?requestTimeout=10",
            "ignition-gateway-http:/echo?useStreaming=true", "ignition-gateway-http:/echo?produces=text/plain"}) {
            var engine = new IgnitionPlatformHttpEngine();
            assertThrows(Exception.class, () -> new CamelRouteRuntime("bad",
                new CamelRouteResource(YAML.replace("ignition-gateway-http:/echo?httpMethodRestrict=POST", uri)), engine::install), uri);
        }
        var first = new IgnitionPlatformHttpEngine();
        var second = new IgnitionPlatformHttpEngine();
        try (var a = new CamelRouteRuntime("a", new CamelRouteResource(YAML), first::install);
             var b = new CamelRouteRuntime("b", new CamelRouteResource(YAML), second::install)) {
            assertEquals("a", first.request("/echo", "a", Map.of()).body());
            assertEquals("b", second.request("/echo", "b", Map.of()).body());
            var duplicate = new IgnitionPlatformHttpEngine();
            assertThrows(Exception.class, () -> new CamelRouteRuntime("duplicate",
                new CamelRouteResource(YAML + YAML), duplicate::install));
            assertEquals(List.of(), duplicate.registeredPaths());
        }
    }

    @Test
    void rejectsDuplicatePublicConsumersAcrossResourcesAndReleasesOnStop() throws Exception {
        var paths = new CamelHttpPathRegistry();
        var first = new IgnitionPlatformHttpEngine(16, paths, "orders");
        var second = new IgnitionPlatformHttpEngine(16, paths, "other");
        var source = YAML.replace("/echo?", "/v1/orders/submit?");
        try (var runtime = new CamelRouteRuntime("orders", new CamelRouteResource(source), first::install)) {
            assertEquals("orders", paths.owner("/v1/orders/submit"));
            assertThrows(Exception.class, () -> new CamelRouteRuntime("other", new CamelRouteResource(source), second::install));
            assertEquals("orders", paths.owner("/v1/orders/submit"));
            assertEquals("ok", first.request("/v1/orders/submit", "ok", Map.of()).body());
        }
        assertNull(paths.owner("/v1/orders/submit"));
        try (var runtime = new CamelRouteRuntime("replacement", new CamelRouteResource(source), second::install)) {
            assertEquals("other", paths.owner("/v1/orders/submit"));
        }
        assertNull(paths.owner("/v1/orders/submit"));
    }

    @Test
    void registrationComesFromLiveConsumersNotRouteDefinitionsOrPermissions() throws Exception {
        var engine = new IgnitionPlatformHttpEngine();
        var direct = YAML.replace("ignition-gateway-http:/echo?httpMethodRestrict=POST", "direct:echo");
        try (var runtime = new CamelRouteRuntime("non-http", new CamelRouteResource(direct), engine::install)) {
            assertEquals(List.of(), engine.registeredPaths());
        }
        var dynamic = new IgnitionPlatformHttpEngine();
        var context = new java.util.concurrent.atomic.AtomicReference<org.apache.camel.CamelContext>();
        try (var runtime = new CamelRouteRuntime("jython-http",
            new CamelRouteResource("JYTHON", null, "test", "routes.test"), camel -> {
                dynamic.install(camel);
                context.set(camel);
            },
            builder -> {
                builder.from_("ignition-gateway-http:/zebra").routeId("zebra").setBody().constant("z");
                builder.from_("ignition-gateway-http:/alpha").routeId("alpha").setBody().constant("a");
            })) {
            var snapshot = dynamic.registeredPaths();
            assertEquals(List.of("/alpha", "/zebra"), snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add("/fake"));
            context.get().getRouteController().stopRoute("alpha");
            assertEquals(List.of("/zebra"), dynamic.registeredPaths());
            assertEquals(List.of("/alpha", "/zebra"), snapshot);
            context.get().getRouteController().startRoute("alpha");
            assertEquals(List.of("/alpha", "/zebra"), dynamic.registeredPaths());
        }
        assertTrue(dynamic.registeredPaths().isEmpty());
    }

    @Test
    void rejectsSaturationWithoutQueuingAndReleasesThePermitAfterFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var engine = new IgnitionPlatformHttpEngine(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var runtime = new CamelRouteRuntime("bounded",
            new CamelRouteResource("JYTHON", null, "test", "routes.test"), engine::install,
            builder -> builder.from_("ignition-gateway-http:/slow").process(exchange -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("expected failure");
            }))) {
            var first = executor.submit(() -> engine.request("/slow", null, Map.of()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(503, assertThrows(IgnitionPlatformHttpEngine.HttpFailure.class,
                () -> engine.request("/slow", null, Map.of())).status());
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> engine.request("/slow", null, Map.of()));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
