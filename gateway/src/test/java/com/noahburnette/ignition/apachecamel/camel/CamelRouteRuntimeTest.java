package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CamelRouteRuntimeTest {
    @Test
    void loadsNativeYamlAndRestoresCallerClassLoader() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader unrelated = new ClassLoader(null) { };
        Thread.currentThread().setContextClassLoader(unrelated);
        CamelRouteRuntime runtime;
        try {
            runtime = new CamelRouteRuntime("test", new CamelRouteResource("""
                - route:
                    from:
                      uri: direct:hello
                      steps:
                        - setBody:
                            simple: 'Hello ${body}'
                """));
            try (runtime) {
                assertTrue(runtime.isStarted());
                assertSame(unrelated, Thread.currentThread().getContextClassLoader());
                assertEquals("Hello Camel", runtime.request("direct:hello", "Camel"));
                assertSame(unrelated, Thread.currentThread().getContextClassLoader());
            }
            assertFalse(runtime.isStarted());
            assertSame(unrelated, Thread.currentThread().getContextClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void usesTheStandardHttpConnector() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            byte[] response = "accepted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
            exchange.close();
        });
        server.start();
        try (var runtime = new CamelRouteRuntime("http", new CamelRouteResource("""
            - route:
                from:
                  uri: direct:orders
                  steps:
                    - to:
                        uri: http://127.0.0.1:%d/orders
            """.formatted(server.getAddress().getPort())))) {
            assertEquals("accepted", new String(
                (byte[]) runtime.request("direct:orders", null), StandardCharsets.UTF_8
            ));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void camelOwnsHttpRedelivery() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/retry", exchange -> {
            int status = attempts.incrementAndGet() < 3 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try (var runtime = new CamelRouteRuntime("retry", new CamelRouteResource("""
            - route:
                errorHandler:
                  defaultErrorHandler:
                    redeliveryPolicy:
                      maximumRedeliveries: 2
                      redeliveryDelay: 0
                from:
                  uri: direct:retry
                  steps:
                    - to:
                        uri: http://127.0.0.1:%d/retry
            """.formatted(server.getAddress().getPort())))) {
            runtime.request("direct:retry", null);
            assertEquals(3, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validationDoesNotStartConsumers() throws Exception {
        CamelRouteRuntime.validate(new CamelRouteResource("""
            - route:
                from:
                  uri: timer:validation?repeatCount=1
                  steps:
                    - to:
                        uri: http://127.0.0.1:1/must-not-be-called
            """));
        assertThrows(Exception.class, () -> CamelRouteRuntime.validate(
            new CamelRouteResource("- route: [invalid")
        ));
    }

    @Test
    void validationDefersYamlBeanConstructionAndFactoriesUntilStartup() throws Exception {
        ValidationBean.constructions.set(0);
        ValidationBean.factories.set(0);
        var resource = new CamelRouteResource("""
            - beans:
                - name: constructed
                  type: %s
                - name: factory
                  type: %s
                  factoryMethod: create
            - route:
                from:
                  uri: direct:validation-beans
                  steps:
                    - to: bean:factory?method=message
            """.formatted(ValidationBean.class.getName(), ValidationBean.class.getName()));

        CamelRouteRuntime.validate(resource);
        assertEquals(0, ValidationBean.constructions.get());
        assertEquals(0, ValidationBean.factories.get());

        try (var runtime = new CamelRouteRuntime("validation-beans", resource)) {
            assertEquals("runtime bean", runtime.request("direct:validation-beans", null));
            assertEquals(2, ValidationBean.constructions.get());
            assertEquals(1, ValidationBean.factories.get());
        }
    }

    public static final class ValidationBean {
        static final AtomicInteger constructions = new AtomicInteger();
        static final AtomicInteger factories = new AtomicInteger();
        public ValidationBean() { constructions.incrementAndGet(); }
        public static ValidationBean create() { factories.incrementAndGet(); return new ValidationBean(); }
        public String message() { return "runtime bean"; }
    }

    @Test
    void validationDefersUnavailableBeanClassesAndPropertyReferences() {
        assertDoesNotThrow(() -> CamelRouteRuntime.validate(new CamelRouteResource("""
            - beans:
                - name: external
                  type: example.NotInstalled
                  properties:
                    password: "{{secret.database}}"
            - from:
                uri: direct:deferred-bean
                steps:
                  - to: bean:external
            """)));
    }

    @Test
    void invalidModelsAreRejectedWithoutInvokingTheirDeclaredFactory() {
        ValidationBean.constructions.set(0);
        ValidationBean.factories.set(0);
        var resource = new CamelRouteResource("""
            - beans:
                - name: invalidModelFactory
                  type: %s
                  factoryMethod: create
            - route:
                from:
                  uri: direct:invalid-model
                  steps:
                    - not-a-camel-step: {}
            """.formatted(ValidationBean.class.getName()));
        assertThrows(Exception.class, () -> CamelRouteRuntime.validate(resource));
        assertEquals(0, ValidationBean.constructions.get());
        assertEquals(0, ValidationBean.factories.get());
        assertThrows(Exception.class, () -> CamelRouteRuntime.validate(new CamelRouteResource("- route: {}")));
    }

    @Test
    void cachedYamlDoesNotBypassConfigurationValidation() throws Exception {
        String yaml = "- route:\n    from:\n      uri: direct:cache-config\n      steps:\n        - to: log:cache-config\n";
        CamelRouteRuntime.validate(new CamelRouteResource(yaml));
        assertThrows(IllegalArgumentException.class, () -> CamelRouteRuntime.validate(
            new CamelRouteResource("invalid-language", yaml, null, null)));
        CamelRouteRuntime.validate(new CamelRouteResource(yaml));
    }

    @Test
    void invalidYamlDoesNotPoisonTheNextStart() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        assertThrows(Exception.class, () -> new CamelRouteRuntime(
            "bad", new CamelRouteResource("- route: [invalid")
        ));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        assertThrows(IllegalArgumentException.class, () -> new CamelRouteRuntime(
            "empty", new CamelRouteResource(" ")
        ));
        loadsNativeYamlAndRestoresCallerClassLoader();
    }
}
