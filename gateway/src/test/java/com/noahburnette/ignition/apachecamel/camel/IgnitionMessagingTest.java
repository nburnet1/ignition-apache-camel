package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.camel.ExchangePattern;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class IgnitionMessagingTest {
    private static final String SESSION = "6afc68d3-2f1e-4dd2-8868-f32d60c913dd";

    @Test void requestUsesTheNativeReplyAndDoesNotForwardCamelHeaders() throws Exception {
        var services = new Services();
        try (var context = context(services); var producer = context.createProducerTemplate()) {
            var result = producer.requestBodyAndHeader("ignition-message:lookup?project=Plant&gateway=Remote&timeoutSec=7",
                Map.of("order", 9007199254740993L), "Authorization", "never-forward", Object.class);
            assertEquals(Map.of("reply", 9007199254740993L), result);
            assertTrue(services.request);
            assertEquals("Plant", services.project); assertEquals("lookup", services.handler);
            assertEquals("Remote", services.gateway); assertEquals(7, services.timeout);
            assertEquals(Map.of("order", 9007199254740993L), services.payload);
        }
    }

    @Test void sendPreservesBodyAndReturnsDispatchStatusOnlyInAHeader() throws Exception {
        var services = new Services();
        try (var context = context(services); var producer = context.createProducerTemplate()) {
            var body = Map.of("order", 1);
            var exchange = producer.send("ignition-message:notify?project=Plant", ExchangePattern.InOnly,
                input -> input.getMessage().setBody(body));
            assertNull(exchange.getException()); assertSame(body, exchange.getMessage().getBody());
            assertEquals(List.of("dispatch-status"), exchange.getMessage().getHeader("ignitionMessageDispatch"));
            assertFalse(services.request); assertNull(services.gateway);
            producer.sendBody("ignition-message:lookup?project=Plant&mode=request", ExchangePattern.InOnly, body);
            assertTrue(services.request);
            producer.requestBody("ignition-message:notify?project=Plant&mode=send", body);
            assertFalse(services.request);
        }
    }

    @Test void perspectiveTargetsAnExplicitPageOrSessionWithoutPretendingToReturnAReply() throws Exception {
        var services = new Services();
        try (var context = context(services); var producer = context.createProducerTemplate()) {
            var body = Map.of("order", 1);
            assertSame(body, producer.requestBody("ignition-perspective-message:updated?sessionId=" + SESSION + "&pageId=main", body));
            assertEquals("updated", services.handler); assertEquals(SESSION, services.session);
            assertEquals("main", services.page); assertEquals("page", services.scope);
            producer.sendBody("ignition-perspective-message:updated?sessionId=" + SESSION + "&scope=session", body);
            assertNull(services.page); assertEquals("session", services.scope);
        }
    }

    @Test void rejectsInvalidConfigurationAndNonObjectPayloads() throws Exception {
        var services = new Services();
        try (var context = context(services); var producer = context.createProducerTemplate()) {
            for (String uri : List.of("ignition-message:lookup", "ignition-message:lookup?project=Plant&mode=other",
                "ignition-message:lookup?project=Plant&timeoutSec=0", "ignition-message:lookup?project=Plant&timeoutSec=301",
                "ignition-message:lookup?project=Plant&roles=admin", "ignition-perspective-message:update",
                "ignition-perspective-message:update?sessionId=invalid&pageId=main",
                "ignition-perspective-message:update?sessionId=" + SESSION,
                "ignition-perspective-message:update?sessionId=" + SESSION + "&scope=view",
                "ignition-perspective-message:update?sessionId=" + SESSION + "&scope=session&pageId=main")) {
                assertThrows(Exception.class, () -> context.getEndpoint(uri), uri);
            }
            for (Object body : List.of("text", List.of(1), 42, Map.of(1, "invalid-key"))) {
                assertThrows(Exception.class, () -> producer.requestBody("ignition-message:lookup?project=Plant", body));
            }
            assertEquals(0, services.calls.get());
            assertThrows(UnsupportedOperationException.class,
                () -> context.getEndpoint("ignition-message:lookup?project=Plant").createConsumer(exchange -> {}));
        }
    }

    @Test void nativeFailureIsAnExchangeFailureNotAnImplicitRetry() throws Exception {
        var services = new Services() {
            @Override public Object message(String project, String handler, String gateway, Object payload, boolean request, int timeout) {
                calls.incrementAndGet(); throw new IllegalStateException("handler failed");
            }
        };
        try (var context = context(services); var producer = context.createProducerTemplate()) {
            assertThrows(Exception.class, () -> producer.requestBody("ignition-message:lookup?project=Plant", Map.of()));
            assertEquals(1, services.calls.get());
        }
    }

    private static DefaultCamelContext context(Services services) throws Exception {
        var context = new DefaultCamelContext();
        new IgnitionComponents(services).install(context);
        context.start(); return context;
    }

    private static class Services implements IgnitionServices {
        final AtomicInteger calls = new AtomicInteger();
        String project, handler, gateway, session, page, scope;
        boolean request; int timeout; Object payload;
        public String requireTagPath(String path) { return path; }
        public AutoCloseable subscribe(String path, Consumer<TagValue> consumer) { throw new UnsupportedOperationException(); }
        public void write(String path, Object value) { throw new UnsupportedOperationException(); }
        public Object invoke(String project, String function, Object body, Map<String, Object> headers) { throw new UnsupportedOperationException(); }
        @Override public Object message(String project, String handler, String gateway, Object payload, boolean request, int timeout) {
            calls.incrementAndGet(); this.project = project; this.handler = handler; this.gateway = gateway;
            this.payload = payload; this.request = request; this.timeout = timeout;
            return request ? Map.of("reply", 9007199254740993L) : List.of("dispatch-status");
        }
        @Override public void perspectiveMessage(String type, String session, String page, String scope, Object payload) {
            calls.incrementAndGet(); this.handler = type; this.session = session;
            this.page = page; this.scope = scope; this.payload = payload;
        }
    }
}
