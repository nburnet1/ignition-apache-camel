package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.camel.support.RoutePolicySupport;

/** Native Camel route-policy counters. Never reads, copies or retains payloads or headers. */
public final class CamelRouteStatistics {
    public static final List<String> METRICS = List.of("started", "completed", "succeeded", "failed", "caughtExceptions",
        "inflight", "meanProcessingMillis", "maxProcessingMillis", "totalProcessingMillis");
    private final Map<String, Counters> routes = new ConcurrentHashMap<>();
    private final long createdAt = System.currentTimeMillis();
    private final java.util.ArrayDeque<Map<String, Object>> failures = new java.util.ArrayDeque<>();

    /** Bounded error metadata only: never bodies, headers, exception messages or endpoint URIs. */
    public synchronized List<Map<String, Object>> recentFailures() { return List.copyOf(failures); }
    private synchronized void recordFailure(String route, String type, String component, boolean handled, long timestamp) {
        if (failures.size() == 20) failures.removeLast();
        failures.addFirst(Map.of("route", route, "type", type, "component", component, "handled", handled, "timestamp", timestamp));
    }

    public void install(CamelContext context) {
        context.addRoutePolicyFactory((camel, id, model) -> {
            var counters = new Counters(id, model);
            routes.put(id, counters);
            return counters;
        });
    }

    public long createdAt() { return createdAt; }

    public List<String> routeIds() { return routes.keySet().stream().sorted().toList(); }

    public Number metric(String route, String key) {
        var counters = routes.get(route);
        return counters == null ? 0 : counters.metric(key);
    }

    public Map<String, Number> aggregate() {
        Map<String, Number> values = new LinkedHashMap<>();
        for (String key : METRICS) values.put(key, aggregateMetric(key));
        values.put("routeCount", routes.size());
        return values;
    }

    public Number aggregateMetric(String key) {
        if ("maxProcessingMillis".equals(key)) return routes.values().stream()
            .mapToDouble(counter -> counter.metric(key).doubleValue()).max().orElse(0);
        if ("meanProcessingMillis".equals(key)) {
            double completed = aggregateMetric("completed").doubleValue();
            return completed == 0 ? 0 : aggregateMetric("totalProcessingMillis").doubleValue() / completed;
        }
        return routes.values().stream().mapToDouble(counter -> counter.metric(key).doubleValue()).sum();
    }

    public List<Map<String, Object>> snapshot() {
        return routes.values().stream().sorted(Comparator.comparing(counter -> counter.id)).map(Counters::snapshot).toList();
    }

    private static String component(String uri) {
        if (uri == null || uri.indexOf(':') < 1) return "unknown";
        return uri.substring(0, uri.indexOf(':'));
    }

    private static List<Map<String, Object>> steps(NamedNode model) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (model instanceof RouteDefinition definition) {
            describe(definition.getOutputs(), 0, result);
        }
        return List.copyOf(result);
    }

    private static void describe(List<ProcessorDefinition<?>> definitions, int depth, List<Map<String, Object>> result) {
        if (depth > 32 || result.size() >= 512) return;
        for (var definition : definitions) {
            if (result.size() >= 512) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", result.size());
            row.put("depth", depth);
            row.put("kind", definition.getShortName());
            // Never expose full endpoint URIs or expression text: they may contain credentials/business data.
            row.put("component", definition instanceof ToDefinition to ? component(to.getUri()) : "");
            result.add(row);
            describe(definition.getOutputs(), depth + 1, result);
        }
    }

    private final class Counters extends RoutePolicySupport {
        private final String id;
        private final String timerKey;
        private final String sourceComponent;
        private final List<Map<String, Object>> steps;
        private final LongAdder started = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder succeeded = new LongAdder();
        private final AtomicLong consecutiveFailures = new AtomicLong();
        private final LongAdder caught = new LongAdder();
        private final LongAdder elapsed = new LongAdder();
        private final AtomicLong inflight = new AtomicLong();
        private final AtomicLong max = new AtomicLong();
        private volatile String state = "STOPPED";
        private volatile String lastErrorType = "";
        private volatile long lastErrorAt;
        private volatile long lastCompletedAt;
        private volatile long lastSucceededAt;
        private volatile long lastFailedAt;

        Counters(String id, NamedNode model) {
            this.id = id;
            timerKey = "apachecamel.camel.time." + java.util.UUID.randomUUID();
            sourceComponent = model instanceof RouteDefinition definition ? component(definition.getEndpointUrl()) : "unknown";
            steps = steps(model);
        }

        @Override public void onStart(Route route) { state = "RUNNING"; }
        @Override public void onStop(Route route) { state = "STOPPED"; }
        @Override public void onSuspend(Route route) { state = "SUSPENDED"; }
        @Override public void onResume(Route route) { state = "RUNNING"; }

        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            started.increment();
            inflight.incrementAndGet();
            synchronized (exchange) {
                @SuppressWarnings("unchecked")
                var times = (java.util.Deque<Long>) exchange.getProperty(timerKey);
                if (times == null) {
                    times = new java.util.ArrayDeque<>();
                    exchange.setProperty(timerKey, times);
                }
                times.push(System.nanoTime());
            }
        }

        @Override
        public void onExchangeDone(Route route, Exchange exchange) {
            Long begin;
            synchronized (exchange) {
                @SuppressWarnings("unchecked")
                var times = (java.util.Deque<Long>) exchange.getProperty(timerKey);
                if (times == null || times.isEmpty()) return;
                begin = times.pop();
                if (times.isEmpty()) exchange.removeProperty(timerKey);
            }
            long duration = Math.max(0, System.nanoTime() - begin);
            elapsed.add(duration);
            max.accumulateAndGet(duration, Math::max);
            Throwable error = exchange.getException();
            Throwable caughtError = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            boolean terminalFailure = exchange.isFailed() || exchange.isRollbackOnly();
            long now = System.currentTimeMillis();
            if (terminalFailure) {
                failed.increment();
                consecutiveFailures.incrementAndGet();
                lastFailedAt = now;
            } else {
                succeeded.increment();
                consecutiveFailures.set(0);
                lastSucceededAt = now;
            }
            if (caughtError != null) caught.increment();
            if (error != null || caughtError != null) {
                lastErrorType = (error == null ? caughtError : error).getClass().getName();
                lastErrorAt = now;
            }
            if (terminalFailure || caughtError != null) {
                Object endpoint = exchange.getProperty(Exchange.FAILURE_ENDPOINT);
                recordFailure(id, error != null ? error.getClass().getName() : caughtError != null ? caughtError.getClass().getName() : "RollbackOnly",
                    endpoint instanceof String uri ? component(uri) : sourceComponent, !terminalFailure, now);
            }
            lastCompletedAt = now;
            inflight.decrementAndGet();
            completed.increment();
        }

        Number metric(String key) {
            return switch (key) {
                case "started" -> started.sum();
                case "completed" -> completed.sum();
                case "failed" -> failed.sum();
                case "succeeded" -> succeeded.sum();
                case "caughtExceptions" -> caught.sum();
                case "inflight" -> inflight.get();
                case "totalProcessingMillis" -> elapsed.sum() / 1_000_000.0;
                case "maxProcessingMillis" -> max.get() / 1_000_000.0;
                case "meanProcessingMillis" -> completed.sum() == 0 ? 0 : elapsed.sum() / 1_000_000.0 / completed.sum();
                default -> 0;
            };
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("state", state);
            result.put("sourceComponent", sourceComponent);
            METRICS.forEach(key -> result.put(key, metric(key)));
            result.put("lastCompletedAt", lastCompletedAt);
            result.put("lastSucceededAt", lastSucceededAt);
            result.put("lastFailedAt", lastFailedAt);
            result.put("consecutiveFailures", consecutiveFailures.get());
            result.put("lastErrorType", lastErrorType);
            result.put("lastErrorAt", lastErrorAt);
            result.put("steps", steps);
            return result;
        }
    }
}
