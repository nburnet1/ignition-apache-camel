package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.junit.jupiter.api.Test;

class IdempotentConsumerExampleTest {
    @Test void duplicateCommandIsSkippedWithoutRepeatingTheProtectedWrite() throws Exception {
        var services = new Services();
        try (var runtime = runtime(services,
            MemoryIdempotentRepository.memoryIdempotentRepository(1000))) {
            assertEquals("delivered", response(runtime, "A", "original"));
            assertEquals("skipped", response(runtime, "A", "changed, same key"));
            assertEquals("delivered", response(runtime, "B", "new key"));
        }
        assertEquals(2, services.deliveries.size());
        assertTrue(services.deliveries.get(0).contains("original"));
        assertFalse(services.deliveries.get(0).contains("changed"));
        assertEquals(List.of("delivered", "skipped", "delivered"), services.results);
    }

    @Test void failedProtectedWriteReleasesTheKeyForRetry() throws Exception {
        var services = new Services();
        var repository = MemoryIdempotentRepository.memoryIdempotentRepository(1000);
        services.failNextDelivery.set(true);
        try (var runtime = runtime(services, repository)) {
            assertThrows(Exception.class, () -> response(runtime, "retry", "first attempt"));
            assertFalse(repository.contains("retry"));
            assertEquals("delivered", response(runtime, "retry", "recovered"));
            assertEquals("skipped", response(runtime, "retry", "duplicate"));
        }
        assertEquals(1, services.deliveries.size());
        assertTrue(services.deliveries.get(0).contains("recovered"));
    }

    @Test void memoryCapacityAndRuntimeReplacementLimitTheDeduplicationWindow() throws Exception {
        var services = new Services();
        try (var runtime = runtime(services,
            MemoryIdempotentRepository.memoryIdempotentRepository(2))) {
            response(runtime, "A", "one");
            response(runtime, "B", "two");
            response(runtime, "C", "three");
            assertEquals("delivered", response(runtime, "A", "evicted key"));
        }
        try (var runtime = runtime(services,
            MemoryIdempotentRepository.memoryIdempotentRepository(2))) {
            assertEquals("delivered", response(runtime, "A", "new runtime"));
        }
    }

    @Test void concurrentDuplicateCommandsProduceOneDeliveryPerId() throws Exception {
        var services = new Services();
        var executor = Executors.newFixedThreadPool(8);
        try (var runtime = runtime(services,
            MemoryIdempotentRepository.memoryIdempotentRepository(1000))) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 200; i++) {
                String id = "concurrent-" + (i % 100);
                futures.add(executor.submit(() -> response(runtime, id, "value")));
            }
            int delivered = 0;
            int skipped = 0;
            for (var future : futures) {
                switch (future.get(10, TimeUnit.SECONDS)) {
                    case "delivered" -> delivered++;
                    case "skipped" -> skipped++;
                    default -> fail("Unexpected Idempotent Consumer result");
                }
            }
            assertEquals(100, delivered);
            assertEquals(100, skipped);
            assertEquals(100, services.deliveries.size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static String response(CamelRouteRuntime runtime, String id, String value)
        throws Exception {
        return (String) runtime.request("direct:idempotent-submit",
            Map.of("id", id, "value", value));
    }

    private static CamelRouteRuntime runtime(Services services,
        IdempotentRepository repository) throws Exception {
        var yaml = Files.readString(Path.of(System.getProperty(
            "camel.examplesDirectory"), "idempotent-consumer.yaml"));
        return new CamelRouteRuntime("idempotent-example-test",
            new CamelRouteResource(yaml), context -> {
                new IgnitionComponents(services).install(context);
                new IgnitionPlatformHttpEngine().install(context);
                context.getRegistry().bind("idem-message-ids", repository);
            });
    }

    private static final class Services implements IgnitionServices {
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        final List<String> results = new CopyOnWriteArrayList<>();
        final AtomicBoolean failNextDelivery = new AtomicBoolean();

        public String requireTagPath(String path) { return path; }
        public AutoCloseable subscribe(String path, Consumer<TagValue> listener) {
            return () -> { };
        }
        public void write(String path, Object value) {
            if (path.endsWith("/LastDelivered")) {
                if (failNextDelivery.compareAndSet(true, false))
                    throw new IllegalStateException("simulated protected write failure");
                deliveries.add(value.toString());
            } else if (path.endsWith("/LastResult")) {
                results.add(value.toString());
            }
        }
        public Object invoke(String project, String function, Object body,
            Map<String, Object> headers) {
            assertEquals("CamelExamples", project);
            assertEquals("idempotent_consumer.validate", function);
            return body;
        }
    }
}
