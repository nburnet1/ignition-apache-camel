package com.noahburnette.ignition.apachecamel.camel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.openjdk.jmh.annotations.*;

/** Real adapters, stub SDK services: lower-bound overhead, NOT Gateway delivery performance. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx512m"})
@State(Scope.Thread)
public class IgnitionComponentAdapterBenchmark {
    @Param({"128", "4096", "32768"})
    public int paddingBytes;

    private DefaultCamelContext context;
    private ProducerTemplate producer;
    private IgnitionComponents components;
    private IgnitionPlatformHttpEngine http;
    private StubServices services;
    private Map<String, Object> body;
    private final Map<String, Object> headers = Map.of("correlationId", "benchmark", "source", "jmh");
    private volatile CountDownLatch arrival;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        body = new LinkedHashMap<>();
        body.put("sequence", 1L);
        body.put("padding", "x".repeat(paddingBytes));
        services = new StubServices();
        components = new IgnitionComponents(services);
        http = new IgnitionPlatformHttpEngine();
        context = new DefaultCamelContext();
        context.disableJMX();
        context.getShutdownStrategy().setTimeout(5);
        components.install(context);
        http.install(context);
        context.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                from("direct:baseline").routeId("benchmark-baseline").process(e -> services.lastValue = e.getMessage().getBody());
                from("direct:tag-write").to("ignition-tag:[default]Benchmark/Value");
                from("direct:script").to("ignition-script:benchmark.echo?project=global");
                from("direct:request").to("ignition-message:benchmarkEcho?project=global&mode=request");
                from("direct:dispatch").to("ignition-message:benchmarkEcho?project=global&mode=send");
                from("direct:perspective").to("ignition-perspective-message:benchmark?sessionId=00000000-0000-0000-0000-000000000001&scope=session");
                from("ignition-gateway-http:/echo").process(e -> services.lastValue = e.getMessage().getBody());
                from("ignition-tag:[default]Benchmark/Input?capacity=1024&includeInitial=false").process(e -> {
                    services.lastValue = e.getMessage().getBody();
                    arrival.countDown();
                });
            }
        });
        context.start();
        producer = context.createProducerTemplate();
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        try {
            // The delivery latch is released inside the route, just before the
            // consumer increments processed. Check the settled counters, not that race.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (components.status().get("received").longValue() != components.status().get("processed").longValue()
                && System.nanoTime() < deadline) Thread.onSpinWait();
            var status = components.status();
            if (status.get("rejected") != 0 || status.get("failed") != 0
                || !status.get("received").equals(status.get("processed"))) {
                throw new IllegalStateException("Native tag delivery did not settle: " + status);
            }
            var httpStatus = http.status();
            if (httpStatus.get("httpRejected") != 0 || httpStatus.get("httpFailed") != 0) {
                throw new IllegalStateException("HTTP adapter failed: " + httpStatus);
            }
        } finally {
            try { producer.stop(); } finally { context.stop(); }
        }
    }

    @Benchmark public Object directBaseline() { return producer.requestBody("direct:baseline", body); }
    @Benchmark public Object tagProducerAdapter() { return producer.requestBody("direct:tag-write", body); }
    @Benchmark public Object scriptProducerAdapter() { return producer.requestBodyAndHeaders("direct:script", body, headers); }
    @Benchmark public Object messageRequestAdapter() { return producer.requestBody("direct:request", body); }
    @Benchmark public Object messageDispatchAdapter() { return producer.requestBody("direct:dispatch", body); }
    @Benchmark public Object perspectiveDispatchAdapter() { return producer.requestBody("direct:perspective", body); }
    @Benchmark public Object httpEngineAdapter() throws Exception { return http.request("/echo", body, headers).body(); }

    @Benchmark
    public Object tagCallbackToDelivery() throws Exception {
        arrival = new CountDownLatch(1);
        services.listener.accept(new IgnitionServices.TagValue(body, "Good", true, 1L, false, "[default]Benchmark/Input"));
        if (!arrival.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Tag was not delivered");
        return services.lastValue;
    }

    /** Explicit test seam: no actual SDK writes, Jython calls, HTTP auth or native messaging here. */
    private static final class StubServices implements IgnitionServices {
        volatile Object lastValue;
        volatile Consumer<TagValue> listener;
        @Override public String requireTagPath(String path) { return path; }
        @Override public AutoCloseable subscribe(String path, Consumer<TagValue> callback) {
            listener = callback;
            return () -> listener = null;
        }
        @Override public void write(String path, Object value) { lastValue = value; }
        @Override public Object invoke(String project, String path, Object value, Map<String, Object> headers) {
            lastValue = value;
            return value;
        }
        @Override public Object message(String project, String handler, String gateway, Object value, boolean request, int timeout) {
            lastValue = value;
            return request ? value : List.of("stub-dispatch");
        }
        @Override public void perspectiveMessage(String type, String session, String page, String scope, Object value) { lastValue = value; }
    }
}
