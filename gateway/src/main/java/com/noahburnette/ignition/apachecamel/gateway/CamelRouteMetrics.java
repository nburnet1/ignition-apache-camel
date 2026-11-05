package com.noahburnette.ignition.apachecamel.gateway;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.inductiveautomation.ignition.gateway.metrics.MetricBuilder;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteStatistics;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Gateway gauges for every definition, including disabled/failed resources, and each live Camel route. */
final class CamelRouteMetrics implements AutoCloseable {
    interface Source {
        java.util.List<String> list();
        java.util.Map<String, String> status();
        java.util.List<String> routeIds(String name);
        Number metric(String name, String route, String key);
    }
    private static final String PREFIX = "apachecamel.camel.";
    private final MetricRegistry registry;
    private final HealthCheckRegistry health;
    private final Source routes;
    private final Set<String> metrics = new HashSet<>();
    private final Set<String> healthChecks = new HashSet<>();

    CamelRouteMetrics(MetricRegistry registry, HealthCheckRegistry health, Source routes) {
        this.registry = registry;
        this.health = health;
        this.routes = routes;
    }

    synchronized void reconcile() {
        Set<String> desired = new HashSet<>();
        register(desired, PREFIX + "all.configured", () -> routes.list().size());
        register(desired, PREFIX + "all.running", () -> routes.status().values().stream().filter("RUNNING"::equals).count());
        register(desired, PREFIX + "all.errors", () -> routes.status().values().stream().filter("ERROR"::equals).count());
        for (String key : CamelRouteStatistics.METRICS) {
            register(desired, PREFIX + "all." + key, () -> categoryMetric(key));
        }
        Set<String> desiredHealth = new HashSet<>();
        for (String name : routes.list()) {
            String prefix = PREFIX + "resource." + segment(name) + ".";
            register(desired, prefix + "running", () -> "RUNNING".equals(routes.status().get(name)) ? 1 : 0);
            for (String key : CamelRouteStatistics.METRICS) {
                register(desired, prefix + key, () -> routes.metric(name, null, key));
            }
            for (String id : routes.routeIds(name)) {
                for (String key : CamelRouteStatistics.METRICS) {
                    register(desired, prefix + "route." + segment(id) + "." + key, () -> routes.metric(name, id, key));
                }
            }
            String check = prefix + "status";
            desiredHealth.add(check);
            if (healthChecks.add(check)) {
                health.unregister(check);
                health.register(check, new HealthCheck() {
                    @Override protected Result check() {
                        String state = routes.status().getOrDefault(name, "MISSING");
                        return "ERROR".equals(state) ? Result.unhealthy(state) : Result.healthy(state);
                    }
                });
            }
        }
        for (String id : Set.copyOf(metrics)) if (!desired.contains(id)) { registry.remove(id); metrics.remove(id); }
        for (String id : Set.copyOf(healthChecks)) if (!desiredHealth.contains(id)) { health.unregister(id); healthChecks.remove(id); }
    }

    private Number categoryMetric(String key) {
        if (key.equals("meanProcessingMillis")) {
            double completed = categoryMetric("completed").doubleValue();
            return completed == 0 ? 0 : categoryMetric("totalProcessingMillis").doubleValue() / completed;
        }
        var values = routes.list().stream().mapToDouble(name -> routes.metric(name, null, key).doubleValue());
        return key.equals("maxProcessingMillis") ? values.max().orElse(0) : values.sum();
    }

    private void register(Set<String> desired, String id, Supplier<Number> value) {
        desired.add(id);
        if (metrics.add(id)) {
            registry.remove(id);
            MetricBuilder.newBuilder().registry(registry).name(id).description("Camel " + id.substring(PREFIX.length()))
                .units(id.endsWith("Millis") ? "milliseconds"
                    : id.endsWith("configured") || id.endsWith("errors") || id.endsWith("running") ? "resources" : "exchanges")
                .getOrAddGauge(value::get);
        }
    }

    /** Encode separators too, preventing collisions between resource names and route IDs. */
    static String segment(String name) {
        StringBuilder result = new StringBuilder();
        for (byte value : name.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 255;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') result.append((char) c);
            else result.append(String.format("%%%02X", c));
        }
        return result.toString();
    }

    @Override public synchronized void close() {
        metrics.forEach(registry::remove);
        healthChecks.forEach(health::unregister);
        metrics.clear();
        healthChecks.clear();
    }
}
