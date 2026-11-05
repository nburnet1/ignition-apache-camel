package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamelRouteMetricsTest {
    @Test
    void registersDisabledDefinitionsAndRemovesDeletedRoutesAndAllMetricsOnClose() {
        var registry = new MetricRegistry();
        var health = new HealthCheckRegistry();
        Map<String, String> states = new HashMap<>(Map.of("enabled", "RUNNING", "disabled", "DISABLED"));
        var source = new CamelRouteMetrics.Source() {
            public List<String> list() { return List.copyOf(states.keySet()); }
            public Map<String, String> status() { return Map.copyOf(states); }
            public List<String> routeIds(String name) { return "enabled".equals(name) ? List.of("route.one") : List.of(); }
            public Number metric(String name, String route, String key) { return "enabled".equals(name) ? 7 : 0; }
        };
        var metrics = new CamelRouteMetrics(registry, health, source);
        metrics.reconcile();
        assertEquals(7, registry.getGauges().get("apachecamel.camel.resource.enabled.completed").getValue());
        assertEquals(0, registry.getGauges().get("apachecamel.camel.resource.disabled.completed").getValue());
        assertTrue(registry.getNames().contains("apachecamel.camel.resource.enabled.route.route%2Eone.completed"));
        states.remove("enabled");
        metrics.reconcile();
        assertFalse(registry.getNames().stream().anyMatch(name -> name.contains(".enabled.")));
        assertFalse(health.getNames().stream().anyMatch(name -> name.contains(".enabled.")));
        metrics.close();
        assertTrue(registry.getNames().isEmpty());
        assertTrue(health.getNames().isEmpty());
        assertNotEquals(CamelRouteMetrics.segment("a.b"), CamelRouteMetrics.segment("a%2Eb"));
    }
}
