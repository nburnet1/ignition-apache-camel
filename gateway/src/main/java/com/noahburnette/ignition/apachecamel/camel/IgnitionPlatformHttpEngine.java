package com.noahburnette.ignition.apachecamel.camel;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.apache.camel.component.platform.http.PlatformHttpComponent;
import org.apache.camel.component.platform.http.PlatformHttpEndpoint;
import org.apache.camel.component.platform.http.spi.PlatformHttpConsumer;
import org.apache.camel.component.platform.http.spi.PlatformHttpEngine;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.service.ServiceSupport;

/** Native ignition-gateway-http consumers, backed by Camel platform-http; Gateway-owned HTTP/authentication. */
public final class IgnitionPlatformHttpEngine extends ServiceSupport implements PlatformHttpEngine {
    private final Map<String, HttpConsumer> consumers = new HashMap<>();
    private final Semaphore requests;
    private final CamelHttpPathRegistry paths;
    private final String resource;
    private final LongAdder received = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public IgnitionPlatformHttpEngine() { this(16); }

    public IgnitionPlatformHttpEngine(int concurrency) {
        this(concurrency, new CamelHttpPathRegistry(), "standalone");
    }

    public IgnitionPlatformHttpEngine(int concurrency, CamelHttpPathRegistry paths, String resource) {
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be positive");
        requests = new Semaphore(concurrency);
        this.paths = java.util.Objects.requireNonNull(paths);
        this.resource = java.util.Objects.requireNonNull(resource);
    }

    public void install(CamelContext context) {
        var component = new PlatformHttpComponent();
        component.setEngine(this);
        context.addComponent("ignition-gateway-http", component);
    }

    @Override
    public PlatformHttpConsumer createConsumer(PlatformHttpEndpoint endpoint, Processor processor) {
        // Validate Camel's resolved consumer path. The servlet separately rejects
        // encoded/ambiguous raw request paths before any owner lookup.
        if (!CamelHttpPublicPaths.validPublicPath(endpoint.getPath())
            || endpoint.isMatchOnUriPrefix() || endpoint.isHttpProxy()) {
            throw new IllegalArgumentException("ignition-gateway-http requires an exact unencoded path, e.g. /v1/orders/submit");
        }
        if (endpoint.getHttpMethodRestrict() != null && !"POST".equals(endpoint.getHttpMethodRestrict())) {
            throw new IllegalArgumentException("ignition-gateway-http currently supports POST only");
        }
        if (!jsonType(endpoint.getConsumes()) || !jsonType(endpoint.getProduces())
            || endpoint.isUseStreaming() || endpoint.isUseCookieHandler() || !endpoint.isUseBodyHandler()
            || endpoint.isReturnHttpRequestHeaders() || endpoint.isHandleWriteResponseError()
            || endpoint.getFileNameExtWhitelist() != null || endpoint.getRequestTimeout() != 0
            || endpoint.getOauthProfile() != null) {
            throw new IllegalArgumentException("ignition-gateway-http supports bounded JSON requests/responses with Gateway authentication, not streaming, cookies, uploads, OAuth or requestTimeout");
        }
        return new HttpConsumer(endpoint, processor);
    }

    private static boolean jsonType(String value) {
        return value == null || "application/json".equalsIgnoreCase(value);
    }

    /** Called only after the Gateway has authorized and bounded/decoded the request. */
    public Reply request(String path, Object body, Map<String, Object> headers) throws Exception {
        HttpConsumer consumer;
        synchronized (consumers) { consumer = consumers.get(path); }
        if (consumer == null) throw new HttpFailure(404, "No active HTTP route at this path");
        received.increment();
        if (!requests.tryAcquire()) {
            rejected.increment();
            throw new HttpFailure(503, "HTTP route is busy");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            var reply = consumer.request(body, headers);
            completed.increment();
            return reply;
        } catch (Exception error) {
            failed.increment();
            throw error;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            requests.release();
        }
    }

    public Map<String, Long> status() {
        return Map.of("httpReceived", received.sum(), "httpRejected", rejected.sum(),
            "httpCompleted", completed.sum(), "httpFailed", failed.sum());
    }

    /** Live registrations only; no route parsing, source exposure or user-code execution. */
    public List<String> registeredPaths() {
        synchronized (consumers) {
            return consumers.entrySet().stream().filter(entry -> entry.getValue().active)
                .map(Map.Entry::getKey).sorted().toList();
        }
    }

    @Override
    protected void doStop() {
        synchronized (consumers) {
            consumers.forEach((path, consumer) -> { consumer.active = false; paths.unregister(path, consumer); });
            consumers.clear();
        }
    }

    public record Reply(int status, Object body) { }

    public static final class HttpFailure extends Exception {
        private final int status;
        public HttpFailure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private final class HttpConsumer extends DefaultConsumer implements PlatformHttpConsumer {
        private volatile boolean active;

        HttpConsumer(PlatformHttpEndpoint endpoint, Processor processor) { super(endpoint, processor); }

        @Override
        public PlatformHttpEndpoint getEndpoint() { return (PlatformHttpEndpoint) super.getEndpoint(); }

        @Override
        protected void doStart() throws Exception {
            super.doStart();
            synchronized (consumers) {
                if (consumers.containsKey(getEndpoint().getPath())) {
                    throw new IllegalArgumentException("Duplicate HTTP path in this resource: " + getEndpoint().getPath());
                }
                paths.register(resource, getEndpoint().getPath(), this);
                active = true;
                consumers.put(getEndpoint().getPath(), this);
            }
        }

        @Override
        protected void doStop() throws Exception {
            active = false;
            synchronized (consumers) {
                consumers.remove(getEndpoint().getPath(), this);
                paths.unregister(getEndpoint().getPath(), this);
            }
            super.doStop();
        }

        Reply request(Object body, Map<String, Object> headers) throws Exception {
            if (!active) throw new HttpFailure(503, "HTTP route is stopping");
            Exchange exchange = createExchange(false);
            exchange.setPattern(ExchangePattern.InOut);
            exchange.getMessage().setBody(body);
            exchange.getMessage().setHeaders(new HashMap<>(headers));
            try {
                createUoW(exchange);
                getProcessor().process(exchange);
                if (exchange.getException() != null) throw exchange.getException();
                Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                if (status != null && (status < 200 || status > 599)) {
                    throw new IllegalArgumentException("Invalid HTTP response status");
                }
                return new Reply(status == null ? 200 : status, exchange.getMessage().getBody());
            } finally {
                doneUoW(exchange);
                releaseExchange(exchange, false);
            }
        }
    }
}
