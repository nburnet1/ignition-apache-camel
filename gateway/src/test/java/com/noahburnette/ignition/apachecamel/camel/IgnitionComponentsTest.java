package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IgnitionComponentsTest {
    @Test
    void wildcardConsumerUsesTheActualChangedPathAndUnsubscribes() throws Exception {
        for (String pattern : new String[]{"[default]Machines/*/Complete", "[default]Machines/**/Complete",
            "[default]Machines/press-?/Complete"}) {
            var services = new FakeServices();
            var components = new IgnitionComponents(services);
            String uri = pattern.contains("?")
                ? "ignition-tag:watch?path=RAW(" + pattern + ")&includeInitial=true"
                : "ignition-tag:" + pattern + "?includeInitial=true";
            try (var runtime = runtime(components, """
                - route:
                    from:
                      uri: '%s'
                      steps:
                        - to: ignition-script:project.transform
                        - to: ignition-tag:[default]Output
                """.formatted(uri))) {
                assertEquals(pattern, services.subscriptionPath);
                services.listener.accept(new IgnitionServices.TagValue(41, "Good", true, 20, true,
                    "[default]Machines/press-1/Complete"));
                assertTrue(services.written.await(5, TimeUnit.SECONDS));
                assertEquals("[default]Machines/press-1/Complete", services.headers.get().get("ignitionTagPath"));
                assertEquals(true, services.headers.get().get("ignitionTagInitial"));
            }
            assertEquals(1, services.unsubscribed.get());
        }
    }

    @Test
    void wildcardWritesAndProviderPatternsAreRejected() {
        for (String endpoint : new String[]{"ignition-tag:[default]Machines/*/Complete",
            "ignition-tag:[default]Machines/**/Complete", "ignition-tag:watch?path=RAW([default]Machines/press-?/Complete)",
            "ignition-tag:[def*]Input", "ignition-tag:[default]Input.Quality"}) {
            assertThrows(Exception.class, () -> runtime(new IgnitionComponents(new FakeServices()), """
                - route:
                    from:
                      uri: direct:invalid
                      steps:
                        - to: '%s'
                """.formatted(endpoint)), endpoint);
        }
    }

    @Test
    void subscribesTransformsAndWritesOffTheCallbackThread() throws Exception {
        var services = new FakeServices();
        var components = new IgnitionComponents(services);
        try (var runtime = runtime(components, """
            - route:
                from:
                  uri: ignition-tag:[default]Input
                  steps:
                    - to: ignition-script:project.transform
                    - to: ignition-tag:[default]Output
            """)) {
            Consumer<IgnitionServices.TagValue> callback = services.listener;
            callback.accept(new IgnitionServices.TagValue(1, "Good", true, 10, true));
            assertEquals(0, components.status().get("received"));
            callback.accept(new IgnitionServices.TagValue(41, "Good", true, 20, false));
            assertTrue(services.written.await(5, TimeUnit.SECONDS));
            assertEquals(42, services.value.get());
            assertEquals("[default]Output", services.path.get());
            assertEquals("[default]Input", services.headers.get().get("ignitionTagPath"));
            assertEquals(20L, services.headers.get().get("ignitionTagTimestamp"));
            assertNotSame(Thread.currentThread(), services.writeThread.get());
            assertEquals(0, components.status().get("rejected"));
        }
        assertEquals(1, services.unsubscribed.get());
        services.listener.accept(new IgnitionServices.TagValue(99, "Good", true, 30, false));
        assertEquals(42, services.value.get());
    }

    @Test
    void overflowRejectsWithoutBlockingTheTagCallback() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var services = new FakeServices() {
            @Override
            public Object invoke(String project, String path, Object body, Map<String, Object> headers) throws Exception {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return body;
            }
        };
        var components = new IgnitionComponents(services);
        try (var runtime = runtime(components, """
            - route:
                from:
                  uri: ignition-tag:[default]Input?capacity=1&includeInitial=true
                  steps:
                    - to: ignition-script:project.slow
            """)) {
            var event = new IgnitionServices.TagValue(1, "Good", true, 10, true);
            services.listener.accept(event);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            services.listener.accept(event);
            services.listener.accept(event);
            assertEquals(3, components.status().get("received"));
            assertEquals(1, components.status().get("rejected"));
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void scriptFailureIsRetriedByCamelAndHeadersAreDefensive() throws Exception {
        var calls = new AtomicInteger();
        var services = new FakeServices() {
            @Override
            public Object invoke(String project, String path, Object body, Map<String, Object> headers) {
                headers.put("injected", "not part of the exchange");
                if (calls.incrementAndGet() < 3) { throw new IllegalStateException("retry me"); }
                return null;
            }
        };
        try (var runtime = runtime(new IgnitionComponents(services), """
            - route:
                errorHandler:
                  defaultErrorHandler:
                    redeliveryPolicy:
                      maximumRedeliveries: 2
                      redeliveryDelay: 0
                from:
                  uri: direct:script
                  steps:
                    - to: ignition-script:project.retry
                    - setBody:
                        simple: '${header.injected}'
            """)) {
            assertNull(runtime.request("direct:script", "body"));
            assertEquals(3, calls.get());
        }
    }

    @Test
    void passesAnExplicitProjectAndNoneRemainsANullBody() throws Exception {
        var projectName = new AtomicReference<String>();
        var services = new FakeServices() {
            @Override
            public Object invoke(String project, String path, Object body, Map<String, Object> headers) {
                projectName.set(project);
                return null;
            }
        };
        try (var runtime = runtime(new IgnitionComponents(services), """
            - route:
                from:
                  uri: direct:null-body
                  steps:
                    - to: ignition-script:project.none?project=explicit-project
                    - setBody:
                        constant: continued
            """)) {
            assertEquals("continued", runtime.request("direct:null-body", "payload"));
            assertEquals("explicit-project", projectName.get());
        }
    }

    @Test
    void rejectsUnknownOptionsAndScriptInjection() {
        for (String endpoint : new String[]{
            "ignition-tag:[default]Input?capacity=0", "ignition-tag:[default]Input?typo=true",
            "ignition-script:project.fn()", "ignition-script:project.fn?typo=true",
            "ignition-script:inline", "ignition-script:inline?expression=RAW{lambda a: a}",
            "ignition-script:inline?expression=RAW{lambda a,b: a}&typo=true",
            "ignition-script:project.fn?expression=RAW{lambda a,b: a}"
        }) {
            assertThrows(Exception.class, () -> runtime(new IgnitionComponents(new FakeServices()), """
                - route:
                    from:
                      uri: direct:invalid
                      steps:
                        - to: '%s'
                """.formatted(endpoint)), endpoint);
        }
    }

    private static CamelRouteRuntime runtime(IgnitionComponents components, String yaml) throws Exception {
        return new CamelRouteRuntime("endpoints-test", new CamelRouteResource(yaml), components::install);
    }

    private static class FakeServices implements IgnitionServices {
        volatile Consumer<TagValue> listener;
        volatile String subscriptionPath;
        final CountDownLatch written = new CountDownLatch(1);
        final AtomicReference<Object> value = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<Thread> writeThread = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> headers = new AtomicReference<>();
        final AtomicInteger unsubscribed = new AtomicInteger();

        @Override
        public String requireTagPath(String path) {
            return com.noahburnette.ignition.apachecamel.TagPaths.requireWritable(path).toStringFull();
        }

        @Override
        public String requireTagSubscriptionPath(String path) {
            return com.noahburnette.ignition.apachecamel.TagPaths.requireSubscribable(path).toStringFull();
        }

        @Override
        public AutoCloseable subscribe(String path, Consumer<TagValue> listener) {
            this.subscriptionPath = path;
            this.listener = listener;
            return unsubscribed::incrementAndGet;
        }

        @Override
        public void write(String path, Object value) {
            this.path.set(path);
            this.value.set(value);
            writeThread.set(Thread.currentThread());
            written.countDown();
        }

        @Override
        public Object invoke(String project, String path, Object body, Map<String, Object> headers) throws Exception {
            this.headers.set(headers);
            return ((Number) body).intValue() + 1;
        }
    }
}
