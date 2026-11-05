package com.noahburnette.ignition.apachecamel.camel;

import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;

/** Producer-only adapters to native Ignition messaging, not a new message bus. */
final class IgnitionMessagingComponents {
    static void install(CamelContext context, IgnitionServices services) {
        context.addComponent("ignition-message", new DefaultComponent() {
            @Override protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
                String handler = required(remaining, "message handler");
                String project = required(getAndRemoveParameter(parameters, "project", String.class), "project");
                String gateway = getAndRemoveParameter(parameters, "gateway", String.class);
                if (gateway != null) required(gateway, "gateway");
                String mode = getAndRemoveParameter(parameters, "mode", String.class, "auto");
                int timeout = getAndRemoveParameter(parameters, "timeoutSec", Integer.class, 10);
                if (!java.util.Set.of("auto", "send", "request").contains(mode)) throw new IllegalArgumentException("mode must be auto, send or request");
                if (timeout < 1 || timeout > 300) throw new IllegalArgumentException("timeoutSec must be between 1 and 300");
                return new MessagingEndpoint(uri, this, exchange -> {
                    requireMapping(exchange);
                    boolean request = mode.equals("request") || mode.equals("auto") && exchange.getPattern().isOutCapable();
                    Object result = services.message(project, handler, gateway, exchange.getMessage().getBody(), request, timeout);
                    if (request) exchange.getMessage().setBody(result);
                    else exchange.getMessage().setHeader("ignitionMessageDispatch", result);
                });
            }
        });
        context.addComponent("ignition-perspective-message", new DefaultComponent() {
            @Override protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
                String type = required(remaining, "message type");
                String session = required(getAndRemoveParameter(parameters, "sessionId", String.class), "sessionId");
                try { java.util.UUID.fromString(session); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("sessionId must be a Perspective session UUID"); }
                String scope = getAndRemoveParameter(parameters, "scope", String.class, "page");
                String page = getAndRemoveParameter(parameters, "pageId", String.class);
                if (!java.util.Set.of("session", "page").contains(scope)) throw new IllegalArgumentException("scope must be session or page; Gateway routes have no current view");
                if (scope.equals("page")) required(page, "pageId");
                else if (page != null) throw new IllegalArgumentException("pageId applies only to page scope");
                return new MessagingEndpoint(uri, this, exchange -> {
                    requireMapping(exchange);
                    services.perspectiveMessage(type, session, page, scope, exchange.getMessage().getBody());
                });
            }
        });
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " is required and must be at most 256 characters without control characters");
        return value;
    }

    private static void requireMapping(Exchange exchange) {
        if (!(exchange.getMessage().getBody() instanceof Map<?, ?> mapping) || mapping.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException("Ignition message payload must be an object with string keys");
    }

    private static final class MessagingEndpoint extends DefaultEndpoint {
        private final Processor delivery;
        MessagingEndpoint(String uri, DefaultComponent component, Processor delivery) {
            super(uri, component); this.delivery = delivery;
        }
        @Override public Producer createProducer() {
            return new DefaultProducer(this) {
                @Override public void process(Exchange exchange) throws Exception { delivery.process(exchange); }
            };
        }
        @Override public Consumer createConsumer(Processor processor) {
            throw new UnsupportedOperationException("Ignition messaging endpoints are producer-only; use a project message handler to call system.camel.send/request");
        }
        @Override public boolean isSingleton() { return true; }
    }
}
