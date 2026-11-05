package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Operator guidance from status and bounded counters, never exception messages or message contents. */
public final class CamelDiagnostics {
    private CamelDiagnostics() { }
    public static Map<String, Object> summarize(String state, List<Map<String, Object>> routes, Map<String, Long> counters) {
        return summarize(state, routes, counters, null);
    }
    public static Map<String, Object> summarize(String state, List<Map<String, Object>> routes, Map<String, Long> counters, CamelStartupFailure startup) {
        var issues = new ArrayList<Map<String, String>>();
        if ("ERROR".equals(state)) issues.add(startup == null
            ? issue("STARTUP", "Resource could not start", "Review Definition and Resources, then inspect Gateway logs for the startup cause.")
            : issue(startup.code(), startup.title(), startup.action()));
        for (var route : routes) {
            if (((Number) route.getOrDefault("consecutiveFailures", 0)).longValue() > 0)
                issues.add(issue("LAST_COMPLETION_FAILED", "Last completion failed: " + route.get("id"), guidance(String.valueOf(route.getOrDefault("lastErrorType", "")))));
        }
        if (counters.getOrDefault("rejected", 0L) > 0 || counters.getOrDefault("httpRejected", 0L) > 0)
            issues.add(issue("REJECTED", "Rejections recorded in this runtime", "Inspect ingress rate and queue/admission capacity. Rejected notifications are not retained work; these counters do not prove a current outage."));
        return Map.of("status", "DISABLED".equals(state) ? "DISABLED" : issues.isEmpty() ? "NO_CURRENT_FAILURE_SIGNAL" : "ATTENTION",
            "issues", List.copyOf(issues), "note", "Status is based on startup state and observed completions, not an end-to-end availability or freshness guarantee.");
    }
    private static Map<String, String> issue(String code, String title, String action) { return Map.of("code", code, "title", title, "action", action); }
    private static String guidance(String type) {
        if (type.contains("Timeout")) return "Check downstream availability and configured waits. A timeout does not prove the remote side effect did not happen; do not replay blindly.";
        if (type.contains("Json") || type.contains("Validation")) return "Check the sender's format and required fields. No payload is retained here; inspect the source system or an explicitly instrumented test environment.";
        if (type.contains("Connect") || type.contains("Socket")) return "Check the destination service, network reachability and TLS/authentication configuration.";
        return "Inspect the exception class and Gateway logs. Check scripts, tag quality and downstream services. Fix the cause before restarting or resubmitting work.";
    }
}
