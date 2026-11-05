package com.noahburnette.ignition.apachecamel.camel;

import java.util.Map;
import java.util.function.Consumer;

/** SDK-independent boundary for the native Ignition Camel endpoints. */
public interface IgnitionServices extends AutoCloseable {
    @Override default void close() { }
    String requireTagPath(String path);

    default String requireTagSubscriptionPath(String path) { return requireTagPath(path); }

    AutoCloseable subscribe(String path, Consumer<TagValue> listener) throws Exception;

    void write(String path, Object value) throws Exception;

    Object invoke(String project, String functionPath, Object body, Map<String, Object> headers) throws Exception;

    @FunctionalInterface
    interface Transform {
        Object invoke(Object body, Map<String, Object> headers) throws Exception;
    }

    default Transform compileInline(String project, String expression) throws Exception {
        throw new UnsupportedOperationException("Inline scripting is unavailable");
    }

    default Object message(String project, String handler, String gateway, Object payload, boolean request, int timeoutSec) throws Exception {
        throw new UnsupportedOperationException("Gateway messaging is unavailable");
    }

    default void perspectiveMessage(String messageType, String sessionId, String pageId, String scope, Object payload) throws Exception {
        throw new UnsupportedOperationException("Perspective messaging is unavailable");
    }

    record TagValue(Object value, String quality, boolean good, long timestampMillis, boolean initial, String path) {
        public TagValue(Object value, String quality, boolean good, long timestampMillis, boolean initial) {
            this(value, quality, good, timestampMillis, initial, null);
        }
    }
}
