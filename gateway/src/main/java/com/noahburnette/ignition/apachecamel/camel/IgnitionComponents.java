package com.noahburnette.ignition.apachecamel.camel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;

/** Small native endpoints, not a second routing or retry engine. */
public final class IgnitionComponents {
    private final IgnitionServices services;
    private final LongAdder received = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder processed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder writes = new LongAdder();
    private final LongAdder scriptCalls = new LongAdder();

    public IgnitionComponents(IgnitionServices services) {
        this.services = services;
    }

    public void install(CamelContext context) {
        context.addComponent("ignition-tag", new TagComponent());
        context.addComponent("ignition-script", new ScriptComponent());
        IgnitionMessagingComponents.install(context, services);
    }

    public Map<String, Long> status() {
        return Map.of("received", received.sum(), "rejected", rejected.sum(),
            "processed", processed.sum(), "failed", failed.sum(),
            "writes", writes.sum(), "scriptCalls", scriptCalls.sum());
    }

    private final class TagComponent extends DefaultComponent {
        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
            // A query path keeps '?' glob characters out of the URI's path/query delimiter.
            String path = services.requireTagSubscriptionPath(getAndRemoveParameter(parameters, "path", String.class, remaining));
            int capacity = getAndRemoveParameter(parameters, "capacity", Integer.class, 1000);
            boolean includeInitial = getAndRemoveParameter(parameters, "includeInitial", Boolean.class, false);
            if (capacity < 1 || capacity > 1_000_000) {
                throw new IllegalArgumentException("capacity must be between 1 and 1000000");
            }
            return new TagEndpoint(uri, this, path, capacity, includeInitial);
        }
    }

    private final class TagEndpoint extends DefaultEndpoint {
        private final String path;
        private final int capacity;
        private final boolean includeInitial;

        TagEndpoint(String uri, TagComponent component, String path, int capacity, boolean includeInitial) {
            super(uri, component);
            this.path = path;
            this.capacity = capacity;
            this.includeInitial = includeInitial;
        }

        @Override
        public Producer createProducer() {
            services.requireTagPath(path);
            return new DefaultProducer(this) {
                @Override
                public void process(Exchange exchange) throws Exception {
                    services.write(path, exchange.getMessage().getBody());
                    writes.increment();
                }
            };
        }

        @Override
        public Consumer createConsumer(Processor processor) throws Exception {
            var consumer = new TagConsumer(this, processor);
            configureConsumer(consumer);
            return consumer;
        }

        @Override
        public boolean isSingleton() { return true; }
    }

    private final class TagConsumer extends DefaultConsumer {
        private final TagEndpoint endpoint;
        private volatile boolean active;
        private ThreadPoolExecutor worker;
        private AutoCloseable subscription;

        TagConsumer(TagEndpoint endpoint, Processor processor) {
            super(endpoint, processor);
            this.endpoint = endpoint;
        }

        @Override
        protected void doStart() throws Exception {
            super.doStart();
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(endpoint.capacity), task -> {
                    Thread thread = new Thread(task, "camel-ignition-tag");
                    thread.setDaemon(true);
                    thread.setContextClassLoader(IgnitionComponents.class.getClassLoader());
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
            active = true;
            try {
                subscription = services.subscribe(endpoint.path, this::accept);
            } catch (Exception error) {
                active = false;
                worker.shutdownNow();
                throw error;
            }
        }

        private void accept(IgnitionServices.TagValue value) {
            if (!active || (value.initial() && !endpoint.includeInitial)) {
                return;
            }
            received.increment();
            try {
                worker.execute(() -> deliver(value));
            } catch (RejectedExecutionException overflow) {
                // Never block a Gateway tag callback, and never silently lose overflow.
                rejected.increment();
            }
        }

        private void deliver(IgnitionServices.TagValue value) {
            if (!active) { return; }
            Exchange exchange = createExchange(false);
            exchange.getMessage().setBody(value.value());
            exchange.getMessage().setHeader("ignitionTagPath", value.path() == null ? endpoint.path : value.path());
            exchange.getMessage().setHeader("ignitionTagQuality", value.quality());
            exchange.getMessage().setHeader("ignitionTagGood", value.good());
            exchange.getMessage().setHeader("ignitionTagTimestamp", value.timestampMillis());
            exchange.getMessage().setHeader("ignitionTagInitial", value.initial());
            try {
                createUoW(exchange);
                getProcessor().process(exchange);
                if (exchange.getException() != null) { throw exchange.getException(); }
                processed.increment();
            } catch (Exception error) {
                failed.increment();
                getExceptionHandler().handleException("Ignition tag route failed", exchange, error);
            } finally {
                doneUoW(exchange);
                releaseExchange(exchange, false);
            }
        }

        @Override
        protected void doStop() throws Exception {
            active = false;
            try {
                if (subscription != null) {
                    subscription.close();
                    subscription = null;
                }
            } finally {
                if (worker != null) {
                    worker.shutdownNow();
                    worker.awaitTermination(5, TimeUnit.SECONDS);
                }
                super.doStop();
            }
        }
    }

    private final class ScriptComponent extends DefaultComponent {
        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
            boolean inline = remaining.equals("inline");
            if (!inline && !remaining.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                throw new IllegalArgumentException("script endpoint must name a project-library function");
            }
            String project = getAndRemoveParameter(parameters, "project", String.class);
            String expression = inline ? getAndRemoveParameter(parameters, "expression", String.class) : null;
            if (inline) InlineScript.source(expression);
            return new DefaultEndpoint(uri, this) {
                @Override
                public Producer createProducer() {
                    return new DefaultProducer(this) {
                        private IgnitionServices.Transform transform;

                        @Override
                        protected void doStart() throws Exception {
                            super.doStart();
                            if (inline) transform = services.compileInline(project, expression);
                        }

                        @Override
                        protected void doStop() throws Exception {
                            transform = null;
                            super.doStop();
                        }

                        @Override
                        public void process(Exchange exchange) throws Exception {
                            var headers = new LinkedHashMap<>(exchange.getMessage().getHeaders());
                            Object result = inline ? transform.invoke(exchange.getMessage().getBody(), headers)
                                : services.invoke(project, remaining, exchange.getMessage().getBody(), headers);
                            scriptCalls.increment();
                            // None becomes a null body; it does not filter the exchange.
                            exchange.getMessage().setBody(result);
                        }
                    };
                }

                @Override
                public Consumer createConsumer(Processor processor) {
                    throw new UnsupportedOperationException("ignition-script is producer-only");
                }

                @Override
                public boolean isSingleton() { return true; }
            };
        }
    }
}
