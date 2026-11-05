package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CamelRouteStatisticsTest {
    @Test
    void representsIntentionallyStoppedRoutesWithoutPretendingTheyAreStillStarting() throws Exception {
        try (var runtime = new CamelRouteRuntime("stopped", new CamelRouteResource("""
            - route:
                id: stopped
                autoStartup: false
                from:
                  uri: direct:stopped
                  steps:
                    - to: log:stopped
            """))) {
            assertEquals("STOPPED", runtime.statistics().snapshot().get(0).get("state"));
            assertEquals(0, runtime.statistics().metric("stopped", "started").intValue());
        }
    }

    @Test
    void countsEveryVisitedRouteAndReportsLatencyWithoutExposingUrisOrPayloads() throws Exception {
        try (var runtime = new CamelRouteRuntime("statistics", new CamelRouteResource("""
            - route:
                id: ingress
                from:
                  uri: direct:start
                  steps:
                    - to: direct:inner
            - route:
                id: inner
                from:
                  uri: direct:inner
                  steps:
                    - setBody:
                        constant: private-business-value
            """))) {
            assertEquals("private-business-value", runtime.request("direct:start", "sensitive-payload"));
            var stats = runtime.statistics();
            assertEquals(1, stats.metric("ingress", "started").intValue());
            assertEquals(1, stats.metric("inner", "completed").intValue());
            assertEquals(0, stats.metric("inner", "inflight").intValue());
            assertEquals(2, stats.aggregateMetric("completed").intValue());
            assertTrue(stats.metric("ingress", "meanProcessingMillis").doubleValue() > 0);
            String snapshot = stats.snapshot().toString();
            assertFalse(snapshot.contains("private-business-value"));
            assertFalse(snapshot.contains("sensitive-payload"));
            assertFalse(snapshot.contains("direct:start"));
            assertTrue(snapshot.contains("sourceComponent=direct"));
            assertTrue(snapshot.contains("kind=to"));
        }
    }

    @Test
    void distinguishesUnhandledFailuresFromHandledExceptions() throws Exception {
        var reference = new CamelRouteResource("JYTHON", null, "test", "routes.build");
        try (var runtime = new CamelRouteRuntime("failure", reference, context -> { }, builder -> {
            builder.onException(IllegalArgumentException.class).handled(true);
            builder.from_("direct:fail").routeId("fail").process(exchange -> { throw new IllegalStateException("do-not-expose-secret"); });
            builder.from_("direct:handled").routeId("handled").process(exchange -> { throw new IllegalArgumentException("do-not-expose-secret"); });
        })) {
            assertThrows(Exception.class, () -> runtime.request("direct:fail", null));
            runtime.request("direct:handled", null);
            assertEquals(1, runtime.statistics().metric("fail", "failed").intValue());
            assertEquals(0, runtime.statistics().metric("handled", "failed").intValue());
            assertEquals(1, runtime.statistics().metric("handled", "caughtExceptions").intValue());
            assertEquals(1, runtime.statistics().metric("handled", "succeeded").intValue());
            assertFalse(runtime.statistics().snapshot().toString().contains("do-not-expose-secret"));
            assertFalse(runtime.statistics().recentFailures().toString().contains("do-not-expose-secret"));
            assertEquals(2, runtime.statistics().recentFailures().size());
            assertTrue(runtime.statistics().recentFailures().stream().anyMatch(row -> Boolean.TRUE.equals(row.get("handled"))));
        }
    }

    @Test void errorMetadataIsBoundedAndSuccessClearsTheCurrentFailureSignalWithoutErasingHistory() throws Exception {
        var reference = new CamelRouteResource("JYTHON", null, "test", "routes.build");
        try (var runtime = new CamelRouteRuntime("bounded-failures", reference, context -> {}, builder -> {
            builder.errorHandler(builder.noErrorHandler());
            builder.from_("direct:probe").routeId("probe").process(exchange -> {
                if (Boolean.TRUE.equals(exchange.getMessage().getHeader("fail"))) throw new IllegalStateException("private-exception-message");
            });
        })) {
            Object body = new Object() { @Override public String toString() { throw new AssertionError("Never inspect the payload"); } };
            assertSame(body, runtime.request("direct:probe", body));
            for (int i = 0; i < 25; i++) assertThrows(Exception.class, () -> runtime.request("direct:probe", "private-body", Map.of("fail", true)));
            var statistics = runtime.statistics();
            assertEquals(20, statistics.recentFailures().size());
            assertEquals(25L, ((Number) statistics.snapshot().get(0).get("consecutiveFailures")).longValue());
            assertTrue(((Number) statistics.snapshot().get(0).get("lastFailedAt")).longValue() > 0);
            assertEquals("ATTENTION", CamelDiagnostics.summarize("RUNNING", statistics.snapshot(), Map.of()).get("status"));
            assertEquals("recovered", runtime.request("direct:probe", "recovered"));
            assertEquals(0L, ((Number) statistics.snapshot().get(0).get("consecutiveFailures")).longValue());
            assertTrue(((Number) statistics.snapshot().get(0).get("lastSucceededAt")).longValue() > 0);
            assertEquals("NO_CURRENT_FAILURE_SIGNAL", CamelDiagnostics.summarize("RUNNING", statistics.snapshot(), Map.of()).get("status"));
            assertEquals(25, statistics.metric("probe", "failed").intValue());
            assertFalse(statistics.recentFailures().toString().contains("private-"));
            assertFalse(statistics.recentFailures().toString().contains("direct:probe"));
        }
    }

    @Test
    void tracksInflightAndNewRuntimeResetsCounters() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var reference = new CamelRouteResource("JYTHON", null, "test", "routes.build");
        try (var runtime = new CamelRouteRuntime("inflight", reference, context -> { }, builder ->
            builder.from_("direct:test").routeId("test").process(exchange -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }))) {
            var call = executor.submit(() -> runtime.request("direct:test", Map.of("value", 1)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, runtime.statistics().metric("test", "inflight").intValue());
            release.countDown();
            call.get(5, TimeUnit.SECONDS);
            assertEquals(0, runtime.statistics().metric("test", "inflight").intValue());
        } finally { release.countDown(); executor.shutdownNow(); }
        try (var runtime = new CamelRouteRuntime("replacement", reference, context -> { }, builder ->
            builder.from_("direct:test").routeId("test").setBody().constant("replacement"))) {
            assertEquals(0, runtime.statistics().metric("test", "completed").intValue());
        }
    }
}
