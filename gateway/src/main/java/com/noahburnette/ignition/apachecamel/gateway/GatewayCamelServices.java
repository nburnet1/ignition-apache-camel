package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.noahburnette.ignition.apachecamel.TagPaths;
import com.noahburnette.ignition.apachecamel.camel.IgnitionServices;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.Consumer;
import org.python.core.Py;
import org.python.core.PyObject;
import com.inductiveautomation.ignition.common.script.ScriptFunction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import com.inductiveautomation.ignition.common.tags.model.SecurityContext;

/** All SDK calls stay on this side of the endpoint boundary. */
final class GatewayCamelServices implements IgnitionServices {
    private static final Gson GSON = JsonResourceCodec.buildGson(null);
    private final GatewayContext context;
    private final GatewayTagWriter writer;
    private final Semaphore messaging = new Semaphore(16);
    private final ThreadPoolExecutor messageWorkers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "camel-ignition-message");
            thread.setDaemon(true);
            thread.setContextClassLoader(GatewayCamelServices.class.getClassLoader());
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private volatile ScriptFunction messageFunction;
    private volatile ScriptFunction perspectiveFunction;

    GatewayCamelServices(GatewayContext context) {
        this.context = context;
        writer = new GatewayTagWriter(context);
    }

    @Override
    public String requireTagPath(String path) {
        return TagPaths.requireWritable(path).toStringFull();
    }

    @Override
    public String requireTagSubscriptionPath(String path) {
        return TagPaths.requireSubscribable(path).toStringFull();
    }

    @Override
    public AutoCloseable subscribe(String path, Consumer<TagValue> listener) throws Exception {
        var parsed = TagPaths.requireSubscribable(path);
        TagChangeListener callback = change -> {
            var value = change.getValue();
            listener.accept(new TagValue(value.getValue(), value.getQuality().toString(),
                value.getQuality().isGood(), value.getTimestamp().getTime(), change.isInitial(),
                change.getTagPath().toStringFull()));
        };
        return NativeTagSubscriptions.subscribe(context.getTagManager(), parsed, callback);
    }

    @Override
    public void write(String path, Object value) throws Exception {
        writer.write(TagPaths.requireWritable(path), value);
    }

    @Override
    public Object invoke(String project, String functionPath, Object body, Map<String, Object> headers) throws Exception {
        return invokeTransform(GatewayScriptFunctions.transform(context, project, functionPath), body, headers);
    }

    @Override
    public Transform compileInline(String project, String expression) throws Exception {
        var function = GatewayScriptFunctions.compile(context, project,
            com.noahburnette.ignition.apachecamel.camel.InlineScript.source(expression));
        return (body, headers) -> invokeTransform(function, body, headers);
    }

    static Object invokeTransform(ScriptFunction function,
        Object body, Map<String, Object> headers) throws Exception {
        PyObject result = function.invoke(
            TypeUtilities.gsonToPy(GSON.toJsonTree(body)),
            TypeUtilities.gsonToPy(GSON.toJsonTree(headers))
        );
        if (result == null || result == Py.None) { return null; }
        JsonElement json = TypeUtilities.pyToGson(result, GSON);
        if (json == null) {
            throw new IllegalArgumentException("script must return a JSON-compatible value or None");
        }
        return toJava(json);
    }

    @Override
    public Object message(String project, String handler, String gateway, Object payload, boolean request, int timeoutSec) throws Exception {
        if (!messaging.tryAcquire()) throw new RejectedExecutionException("Ignition messaging concurrency limit reached");
        final PyObject dictionary;
        try { dictionary = messagePayload(payload); }
        catch (RuntimeException failure) { messaging.release(); throw failure; }
        var security = SecurityContext.THREAD_LOCAL.get();
        final java.util.concurrent.Future<Object> task;
        try {
            task = messageWorkers.submit(() -> {
                var previous = SecurityContext.THREAD_LOCAL.get();
                if (security == null) SecurityContext.THREAD_LOCAL.remove();
                else SecurityContext.THREAD_LOCAL.set(security);
                try { return callNativeMessage(project, handler, gateway, dictionary, request, timeoutSec); }
                finally {
                    if (previous == null) SecurityContext.THREAD_LOCAL.remove();
                    else SecurityContext.THREAD_LOCAL.set(previous);
                    // A timeout does not release admission until the actual native call exits.
                    messaging.release();
                }
            });
        } catch (RejectedExecutionException full) { messaging.release(); throw full; }
        try { return task.get(timeoutSec, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            // Do not cancel a submitted call: native dispatch may already have taken ownership.
            // It keeps its admission permit until the worker really returns.
            throw new TimeoutException("Ignition message request/dispatch timed out; the handler may still execute");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw interrupted;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private Object callNativeMessage(String project, String handler, String gateway, PyObject payload, boolean request, int timeoutSec) throws Exception {
        var caller = Py.getSystemState();
        try {
            // Invoke the native facade, including its security-context handling and Gateway Network policy.
            // The host-owned manager is borrowed; no project import or private interpreter is needed.
            var result = messageFunction().invoke(Py.newString(project), Py.newString(handler), payload,
                gateway == null ? Py.None : Py.newString(gateway), Py.newBoolean(request), Py.newInteger(timeoutSec));
            if (result == null || result == Py.None) return null;
            JsonElement json = TypeUtilities.pyToGson(result, GSON);
            if (json == null) throw new IllegalArgumentException("Message handler must return a JSON-compatible value or None");
            requireMessageSize(json);
            return toJava(json);
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Override public void close() { messageWorkers.shutdownNow(); }

    @Override
    public void perspectiveMessage(String messageType, String sessionId, String pageId, String scope, Object payload) throws Exception {
        if (context.getModuleManager().getModule("com.inductiveautomation.perspective") == null)
            throw new IllegalStateException("Perspective is not installed or available on this Gateway");
        if (!messaging.tryAcquire()) throw new RejectedExecutionException("Ignition messaging concurrency limit reached");
        var caller = Py.getSystemState();
        try {
            perspectiveFunction().invoke(Py.newString(messageType), messagePayload(payload), Py.newString(sessionId),
                pageId == null ? Py.None : Py.newString(pageId), Py.newString(scope));
        } finally {
            Py.setSystemState(caller);
            messaging.release();
        }
    }

    private synchronized ScriptFunction messageFunction() throws Exception {
        if (messageFunction == null) messageFunction = context.getScriptManager().compileFunction("""
            def invoke(project, handler, payload, gateway, request, timeout):
                if request:
                    return system.util.sendRequest(project=project, messageHandler=handler, payload=payload,
                        remoteServer=gateway, timeoutSec=timeout)
                targets = [] if gateway is None else [gateway]
                return system.util.sendMessage(project=project, messageHandler=handler, payload=payload,
                    scope='G', remoteServers=targets)
            """);
        return messageFunction;
    }

    private synchronized ScriptFunction perspectiveFunction() throws Exception {
        if (perspectiveFunction == null) perspectiveFunction = context.getScriptManager().compileFunction("""
            def invoke(messageType, payload, sessionId, pageId, scope):
                if scope == 'session':
                    system.perspective.sendMessage(messageType=messageType, payload=payload,
                        scope=scope, sessionId=sessionId)
                else:
                    system.perspective.sendMessage(messageType=messageType, payload=payload,
                        scope=scope, sessionId=sessionId, pageId=pageId)
            """);
        return perspectiveFunction;
    }

    private static PyObject messagePayload(Object payload) {
        JsonElement json = GSON.toJsonTree(payload);
        if (!json.isJsonObject()) throw new IllegalArgumentException("Ignition message payload must be a JSON object");
        requireMessageSize(json);
        return TypeUtilities.gsonToPy(json);
    }

    private static void requireMessageSize(JsonElement json) {
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024)
            throw new IllegalArgumentException("Ignition messaging payload/reply exceeds 64 KiB");
    }

    /** Preserve integers instead of Gson's default Object decoder's double coercion. */
    static Object toJava(JsonElement json) {
        if (json.isJsonNull()) { return null; }
        if (json.isJsonArray()) {
            var list = new ArrayList<Object>();
            json.getAsJsonArray().forEach(value -> list.add(toJava(value)));
            return list;
        }
        if (json.isJsonObject()) {
            var map = new LinkedHashMap<String, Object>();
            json.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
            return map;
        }
        var primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) { return primitive.getAsBoolean(); }
        if (primitive.isString()) { return primitive.getAsString(); }
        String number = primitive.getAsString();
        if (number.contains(".") || number.contains("e") || number.contains("E")) {
            return new BigDecimal(number);
        }
        BigInteger integer = new BigInteger(number);
        return integer.bitLength() < 64 ? integer.longValue() : integer;
    }
}
