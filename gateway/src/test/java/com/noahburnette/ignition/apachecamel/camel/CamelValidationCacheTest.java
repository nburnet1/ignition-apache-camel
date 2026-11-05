package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CamelValidationCacheTest {
    @Test void cachesSuccessAndFailureButRevalidatesChangedYaml() throws Exception {
        var cache = new CamelValidationCache(4);
        var calls = new AtomicInteger();
        Callable<Void> valid = () -> { calls.incrementAndGet(); return null; };
        cache.validate("good", valid);
        cache.validate("good", valid);
        cache.validate("changed", valid);
        Callable<Void> invalid = () -> { calls.incrementAndGet(); throw new IllegalArgumentException("yaml: invalid step"); };
        for (int i = 0; i < 2; i++) {
            assertEquals("yaml: invalid step", assertThrows(IllegalArgumentException.class,
                () -> cache.validate("bad", invalid)).getMessage());
        }
        assertEquals(3, calls.get());
    }

    @Test void evictsLeastRecentlyUsedResults() throws Exception {
        var cache = new CamelValidationCache(2);
        var calls = new AtomicInteger();
        Callable<Void> valid = () -> { calls.incrementAndGet(); return null; };
        cache.validate("a", valid);
        cache.validate("b", valid);
        cache.validate("a", valid);
        cache.validate("c", valid);
        cache.validate("b", valid);
        assertEquals(4, calls.get());
    }

    @Test void concurrentReadsShareOneParse() throws Exception {
        var cache = new CamelValidationCache(2);
        var calls = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int i = 0; i < 32; i++) tasks.add(() -> {
                cache.validate("same", () -> { calls.incrementAndGet(); return null; });
                return null;
            });
            for (var result : workers.invokeAll(tasks)) result.get();
            assertEquals(1, calls.get());
        } finally { workers.shutdownNow(); }
    }

    @Test void validationContextHasOnlyLocalQuietSettings() throws Exception {
        try (var context = CamelRouteRuntime.newValidationContext();
             var live = new org.apache.camel.impl.DefaultCamelContext()) {
            assertEquals(org.apache.camel.StartupSummaryLevel.Off, context.getStartupSummaryLevel());
            assertEquals("false", context.resolvePropertyPlaceholders("{{camel.main.yamlDslCompactNotationWarn}}"));
            assertFalse(context.isStarted());
            assertNotEquals(org.apache.camel.StartupSummaryLevel.Off, live.getStartupSummaryLevel());
            assertNull(live.resolvePropertyPlaceholders("{{?camel.main.yamlDslCompactNotationWarn}}"));
        }
    }
}
