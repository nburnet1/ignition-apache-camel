package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.camel.Processor;
import org.apache.camel.component.paho.mqtt5.PahoMqtt5Component;
import org.apache.camel.component.paho.mqtt5.PahoMqtt5Persistence;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class CamelMqtt5Test {
    @Test void focusedConfigurationUsesMqtt5WithExplicitVolatileDefaultsAndHostnameVerification() throws Exception {
        var defaults = new CamelComponentResource.Mqtt("tcp://localhost:1883", null, null, null, null, null, null, null);
        assertEquals(0L, defaults.sessionExpiryInterval());
        assertTrue(defaults.cleanStart());
        try (var context = new DefaultCamelContext()) {
            var component = (PahoMqtt5Component) CamelMqtt5.create(context, defaults, null, null);
            try {
                var config = component.getConfiguration();
                assertTrue(config.isCleanStart());
                assertEquals(0L, config.getSessionExpiryInterval());
                assertEquals(PahoMqtt5Persistence.MEMORY, config.getPersistence());
                assertTrue(config.isHttpsHostnameVerificationEnabled());
                assertEquals(1, config.getQos());
                assertEquals(10, config.getConnectionTimeout());
                assertNull(config.getPassword());
            } finally { component.stop(); }
        }
    }

    @Test void customSessionExpiryAndAuthenticationBindWithoutStartingAConnection() throws Exception {
        var settings = new CamelComponentResource.Mqtt("ssl://localhost:8883", "operator",
            new CamelSecretReference("provider", "password"), null, 2, false, 17, 4294967295L);
        assertTrue(new CamelComponentResource("MQTT5", null, settings, null).validationErrors().isEmpty());
        try (var context = new DefaultCamelContext()) {
            var component = (PahoMqtt5Component) CamelMqtt5.create(context, settings, "test-password", null);
            try {
                var config = component.getConfiguration();
                assertFalse(config.isCleanStart());
                assertEquals(4294967295L, config.getSessionExpiryInterval());
                assertEquals("operator", config.getUserName());
                assertEquals("test-password", config.getPassword());
                assertEquals(2, config.getQos());
                assertTrue(config.isHttpsHostnameVerificationEnabled());
                assertFalse(component.isStarted());
            } finally { component.stop(); }
        }
        for (long expiry : new long[]{-1, 4294967296L}) {
            var invalid = new CamelComponentResource.Mqtt("tcp://localhost:1883", null, null, null, 1, true, 10, expiry);
            assertTrue(new CamelComponentResource("MQTT5", null, invalid, null).validationErrors().containsKey("mqtt.sessionExpiryInterval"));
        }
    }

    private record Received(String body, String topic, int qos) { }

    @Test void brokerRoundTripAndRetainedDeliverySurviveContextReplacementUsingTheNamedComponent() throws Exception {
        String broker = System.getenv("CAMEL_MQTT5_TEST_BROKER");
        assumeTrue(broker != null && broker.startsWith("tcp://127.0.0.1:"), "Set CAMEL_MQTT5_TEST_BROKER to an isolated loopback MQTT 5 broker");
        String token = UUID.randomUUID().toString();
        String topic = "camel-tests/mqtt5/" + token;
        var received = new LinkedBlockingQueue<Received>();
        var resource = new CamelRouteResource("""
            - route:
                id: mqtt5-input
                from:
                  uri: named-mqtt:%s?clientId=camel-test-sub-%s
                  steps:
                    - process:
                        ref: observe
            - route:
                id: mqtt5-output
                from:
                  uri: direct:publish
                  steps:
                    - to: named-mqtt:%s?clientId=camel-test-pub-%s&retained=true
            """.formatted(topic, token, topic, token));
        var config = new CamelComponentResource.Mqtt(broker, null, null, null, 1, true, 3, 0L);
        for (int replacement = 0; replacement < 2; replacement++) {
            try (var runtime = new CamelRouteRuntime("mqtt5-test", resource, context -> {
                try { context.addComponent("named-mqtt", CamelMqtt5.create(context, config, null, null)); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                context.getRegistry().bind("observe", (Processor) exchange -> received.add(new Received(
                    exchange.getMessage().getBody(String.class), exchange.getMessage().getHeader("CamelMqttTopic", String.class),
                    exchange.getMessage().getHeader("CamelMqttQoS", Integer.class))));
            })) {
                if (replacement > 0) assertEquals(new Received("MQTT 5 ✓ 9007199254740993", topic, 1), received.poll(10, TimeUnit.SECONDS));
                runtime.send("direct:publish", "MQTT 5 ✓ 9007199254740993", Map.of());
                assertEquals(new Received("MQTT 5 ✓ 9007199254740993", topic, 1), received.poll(10, TimeUnit.SECONDS));
            }
        }
    }
}
