package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CamelRuntimeOwnershipTest {
    private static final CamelRouteResource ROUTE = new CamelRouteResource("""
        - route:
            from:
              uri: direct:test
              steps:
                - setBody:
                    constant: ok
        """);

    @Test void setupClosesAfterRuntimeStops() throws Exception {
        var closes = new AtomicInteger();
        try (var runtime = new CamelRouteRuntime("owned", ROUTE, context -> { }, null, closes::incrementAndGet)) {
            assertEquals("ok", runtime.request("direct:test", "input"));
            assertEquals(0, closes.get());
        }
        assertEquals(1, closes.get());
    }

    @Test void setupClosesWhenConfigurationFails() {
        var closes = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new CamelRouteRuntime("failed", ROUTE,
            context -> { throw new IllegalStateException("setup failed"); }, null, closes::incrementAndGet));
        assertEquals(1, closes.get());
    }
}
