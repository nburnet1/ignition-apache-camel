package com.noahburnette.ignition.apachecamel.camel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

/** Bounded module-local syntax results, keyed by digest; no contexts or exception graphs. */
final class CamelValidationCache {
    private final int capacity;
    private final LinkedHashMap<String, Result> results = new LinkedHashMap<>(16, 0.75f, true);

    CamelValidationCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }

    // Serialize cache misses so concurrent resource reads parse a definition only once.
    // This monitor is independent of runtime reconciliation and never starts consumers.
    synchronized void validate(String yaml, Callable<Void> validation) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(yaml.getBytes(StandardCharsets.UTF_8)));
        Result result = results.get(key);
        if (result == null) {
            try {
                validation.call();
                result = new Result(null);
            } catch (Exception error) {
                result = new Result(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            results.put(key, result);
            if (results.size() > capacity) results.remove(results.keySet().iterator().next());
        }
        if (result.error() != null) throw new IllegalArgumentException(result.error());
    }

    private record Result(String error) { }
}
