package com.noahburnette.ignition.apachecamel.camel;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.apache.camel.CamelContext;
import org.apache.camel.management.DefaultManagementAgent;
import org.apache.camel.management.JmxManagementStrategy;
import org.jolokia.core.api.LogHandler;
import org.jolokia.json.JSONArray;
import org.jolokia.json.JSONObject;
import org.jolokia.json.JSONStructure;
import org.jolokia.json.parser.JSONParser;
import org.jolokia.server.core.config.StaticConfiguration;
import org.jolokia.server.core.config.SystemPropertyMode;
import org.jolokia.server.core.http.HttpRequestHandler;
import org.jolokia.server.core.service.JolokiaServiceManagerFactory;
import org.jolokia.server.core.service.api.JolokiaContext;
import org.jolokia.server.core.service.api.JolokiaServiceManager;
import org.jolokia.server.core.service.api.Restrictor;
import org.jolokia.server.core.util.HttpMethod;
import org.jolokia.server.core.util.RequestType;
import org.jolokia.service.jmx.LocalRequestHandler;
import org.jolokia.service.serializer.JolokiaSerializer;
import org.slf4j.LoggerFactory;

/** Opt-in Jolokia: module-only management by default, or explicit local JVM-wide read-only inspection. */
public final class CamelHawtio implements AutoCloseable {
    public static final String ENABLED_PROPERTY = "apachecamel.camel.hawtio.enabled";
    public static final String CONTROL_PROPERTY = "apachecamel.camel.hawtio.controlEnabled";
    public static final String SCOPE_PROPERTY = "apachecamel.camel.hawtio.scope";
    public static final String MODULE_SCOPE = "module";
    public static final String JVM_READONLY_SCOPE = "jvm-readonly";
    public static final int MAX_REQUEST_BYTES = 65536;
    public static final int MAX_RESPONSE_BYTES = 1048576;
    private static final Set<String> ATTRIBUTES = Set.of(
        "CamelId", "CamelManagementName", "CamelVersion", "ContextId", "RouteId", "State", "Uptime", "UptimeMillis",
        "StatisticsEnabled", "ExchangesTotal", "ExchangesCompleted", "ExchangesFailed", "ExchangesInflight",
        "FailuresHandled", "Redeliveries", "ExternalRedeliveries", "MinProcessingTime", "MaxProcessingTime",
        "MeanProcessingTime", "LastProcessingTime", "TotalProcessingTime", "DeltaProcessingTime",
        "LastExchangeCompletedTimestamp", "LastExchangeFailureTimestamp", "FirstExchangeCompletedTimestamp",
        "FirstExchangeFailureTimestamp", "ResetTimestamp", "StartTimestamp", "Load01", "Load05", "Load15",
        "InflightExchanges", "StartedRoutes", "TotalRoutes", "Tracing", "Suspended");
    private static final Set<String> CONTROLS = Set.of("start", "stop", "suspend", "resume");
    private final MBeanServer server = MBeanServerFactory.newMBeanServer("org.apache.camel");
    private final Semaphore admission = new Semaphore(4);
    private final boolean controlEnabled;
    private final boolean jvmReadonly;
    private final Map<MBeanServer, MBeanServerConnection> connections = new IdentityHashMap<>();
    private final JolokiaServiceManager manager;
    private final HttpRequestHandler handler;
    private volatile boolean closed;

    public CamelHawtio(boolean controlEnabled) {
        this(controlEnabled, MODULE_SCOPE);
    }

    public CamelHawtio(boolean controlEnabled, String scope) {
        if (!Set.of(MODULE_SCOPE, JVM_READONLY_SCOPE).contains(scope)) {
            throw new IllegalArgumentException("Hawtio scope must be module or jvm-readonly");
        }
        jvmReadonly = JVM_READONLY_SCOPE.equals(scope);
        this.controlEnabled = controlEnabled && !jvmReadonly;
        var restrictor = new Policy();
        var configuration = new StaticConfiguration(Map.of(
            "agentId", "apache-camel-camel", "agentType", "ignition",
            "discoveryEnabled", "false", "disableDetectors", "true", "debug", "false",
            "historyMaxEntries", "0", "maxDepth", "12", "maxCollectionSize", "1024",
            "maxObjects", "10000", "includeStackTrace", "false"), null, SystemPropertyMode.NEVER);
        var logger = LoggerFactory.getLogger(CamelHawtio.class);
        manager = JolokiaServiceManagerFactory.createJolokiaServiceManager(configuration, new LogHandler() {
            @Override public boolean isDebug() { return false; }
            @Override public void debug(String message) { logger.debug("{}", message); }
            @Override public void info(String message) { logger.info("{}", message); }
            @Override public void error(String message, Throwable error) { logger.error(message, error); }
        }, restrictor);
        // Explicit services only: no remote JMX proxy, discovery, notifications or history.
        manager.addService(new JolokiaSerializer());
        var access = new LocalMBeanServerAccess(this::localConnections);
        manager.addService(new LocalRequestHandler(1000) {
            @Override public void init(JolokiaContext context) {
                var scoped = (JolokiaContext) Proxy.newProxyInstance(CamelHawtio.class.getClassLoader(),
                    new Class<?>[] { JolokiaContext.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getMBeanServerAccess")) return access;
                        try { return method.invoke(context, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                super.init(scoped);
            }
        });
        try { handler = new HttpRequestHandler(manager.start(), restrictor, true); }
        catch (RuntimeException | Error failure) { manager.stop(); throw failure; }
    }

    /** Called only for live contexts. Validation always retains disableJMX(). */
    public void install(CamelContext context) {
        if (closed) throw new IllegalStateException("Camel management is closed");
        var agent = new DefaultManagementAgent(context);
        agent.setMBeanServer(server);
        agent.setMBeanObjectDomainName("org.apache.camel");
        agent.setMask(true);
        agent.setMBeansLevel(jvmReadonly ? org.apache.camel.ManagementMBeansLevel.Default : org.apache.camel.ManagementMBeansLevel.RoutesOnly);
        agent.setUpdateRouteEnabled(false);
        agent.setRegisterAlways(false);
        var factory = new org.apache.camel.management.JmxManagementStrategyFactory();
        var strategy = new JmxManagementStrategy(context, agent);
        // Upstream processor specialization links directly to camel-log and camel-bean.
        // Those removable components belong to the child extension loader, not this
        // host loader. Use component-independent processor statistics instead.
        strategy.setManagementObjectStrategy(new org.apache.camel.management.DefaultManagementObjectStrategy() {
            @Override public Object getManagedObjectForProcessor(CamelContext camel, org.apache.camel.Processor processor,
                    org.apache.camel.NamedNode node, org.apache.camel.Route route) {
                if (processor instanceof org.apache.camel.spi.ErrorHandler) return false;
                var managed = new org.apache.camel.management.mbean.ManagedProcessor(
                    camel, processor, (org.apache.camel.model.ProcessorDefinition<?>) node);
                managed.setRoute(route);
                managed.init(camel.getManagementStrategy());
                return managed;
            }
        });
        try { factory.setupManagement(context, strategy, factory.createLifecycle(context)); }
        catch (Exception failure) { throw new IllegalStateException("Could not configure scoped Camel management", failure); }
    }

    /** Transport authentication and CSRF are enforced by Gateway routes, not this protocol handler. */
    public String post(byte[] bytes) throws Exception {
        if (bytes.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Management body exceeds 64 KiB");
        if (closed) throw new IllegalStateException("Camel management is closed");
        if (!admission.tryAcquire()) throw new BusyException();
        try {
            if (closed) throw new IllegalStateException("Camel management is closed");
            // Never accept JSR-160 targets, notifications or unbounded bulk work.
            Object request = new JSONParser().parse(new String(bytes, StandardCharsets.UTF_8));
            if (request instanceof JSONArray batch) {
                if (batch.isEmpty() || batch.size() > 32) throw new IllegalArgumentException("Use 1–32 management requests");
                for (Object item : batch) validateRequest(item);
            } else validateRequest(request);
            byte[] normalized = ((JSONStructure) request).toJSONString().getBytes(StandardCharsets.UTF_8);
            JSONStructure response = handler.handlePostRequest("/camel-jolokia", new ByteArrayInputStream(normalized), "UTF-8", Map.of());
            sanitize(response);
            if (jvmReadonly) decorateListResponses(response);
            String json = response.toJSONString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("Management response exceeds 1 MiB; narrow the query");
            }
            return json;
        } finally { admission.release(); }
    }

    private void validateRequest(Object value) {
        if (!(value instanceof JSONObject request)) throw new IllegalArgumentException("Expected a Jolokia request object");
        if (!Set.of("type", "mbean", "attribute", "operation", "arguments", "path", "config").containsAll(request.keySet())) {
            throw new SecurityException("Management processing overrides and remote targets are disabled");
        }
        // Hawtio sends these standard display options. Accept their presence but keep the
        // server's limits/serialization authoritative; never honor client limit increases.
        Object config = request.remove("config");
        if (config != null && (!(config instanceof JSONObject options) || !Set.of(
            "maxDepth", "maxCollectionSize", "maxObjects", "canonicalNaming", "ignoreErrors", "listCache", "ifModifiedSince"
        ).containsAll(options.keySet()))) throw new SecurityException("Management processing override is disabled");
        if (config instanceof JSONObject options && Boolean.TRUE.equals(options.get("listCache"))) {
            var display = new JSONObject();
            display.put("listCache", true); // Metadata de-duplication only; never changes permissions or size limits.
            request.put("config", display);
        }
        String type = String.valueOf(request.get("type"));
        if (!Set.of("version", "search", "list", "read", "exec").contains(type)) {
            throw new SecurityException("Management command is disabled");
        }
        if ("exec".equals(type)) {
            Object arguments = request.get("arguments");
            if (arguments != null && (!(arguments instanceof JSONArray list) || list.size() > 2
                || list.stream().anyMatch(argument -> !(argument instanceof Boolean)))) {
                throw new SecurityException("Only reviewed inspection or zero-argument control signatures are supported");
            }
        }
    }

    private static void sanitize(Object response) {
        if (response instanceof JSONArray batch) { batch.forEach(CamelHawtio::sanitize); return; }
        if (response instanceof JSONObject object && object.get("status") instanceof Number status && status.intValue() >= 400) {
            object.put("error", "Camel management request denied or failed; check permissions and Gateway logs");
            object.remove("stacktrace");
            object.remove("error_value");
        }
    }

    private static void decorateListResponses(Object response) {
        if (response instanceof JSONArray batch) { batch.forEach(CamelHawtio::decorateListResponses); return; }
        if (response instanceof JSONObject object && object.get("request") instanceof JSONObject request
            && "list".equals(request.get("type"))) decorateInspectionMetadata(object.get("value"));
    }

    /** Hawtio otherwise assumes a missing operation has invoke rights, even when absent from JMX metadata. */
    private static void decorateInspectionMetadata(Object value) {
        if (value instanceof JSONArray array) { array.forEach(CamelHawtio::decorateInspectionMetadata); return; }
        if (!(value instanceof JSONObject object)) return;
        if (object.get("class") instanceof String) {
            var operations = object.get("op") instanceof JSONObject found ? found : new JSONObject();
            var signatures = new JSONObject();
            operations.forEach((name, overloads) -> {
                List<?> methods = overloads instanceof JSONArray array ? array : List.of(overloads);
                for (Object entry : methods) {
                    if (!(entry instanceof JSONObject operation)) continue;
                    var args = operation.get("args") instanceof JSONArray array ? array : new JSONArray();
                    String signature = args.stream().map(arg -> String.valueOf(((JSONObject) arg).get("type")))
                        .collect(java.util.stream.Collectors.joining(","));
                    operation.put("canInvoke", true); // Only reviewed methods survived the connection's metadata filter.
                    signatures.put(name + "(" + signature + ")", operation);
                }
            });
            for (String denied : List.of("start", "stop", "suspend", "resume", "restart", "remove", "reset", "startAllRoutes")) {
                var permission = new JSONObject();
                permission.put("canInvoke", false);
                signatures.put(denied + "()", permission);
            }
            object.put("op", operations);
            object.put("opByString", signatures);
            object.put("canInvoke", !operations.isEmpty());
            return;
        }
        object.values().forEach(CamelHawtio::decorateInspectionMetadata);
    }

    private synchronized Set<MBeanServerConnection> localConnections() {
        Set<MBeanServer> local = new LinkedHashSet<>();
        local.add(server); // Module-owned contexts stay private, even in JVM-readonly mode.
        if (jvmReadonly) {
            local.add(java.lang.management.ManagementFactory.getPlatformMBeanServer());
            local.addAll(MBeanServerFactory.findMBeanServer(null));
        }
        connections.keySet().retainAll(local);
        Set<MBeanServerConnection> result = new LinkedHashSet<>();
        for (var source : local) result.add(connections.computeIfAbsent(source, this::filteredConnection));
        return result;
    }

    /** Enforce restrictions at the actual JMX connection as well as in Jolokia's policy. */
    private MBeanServerConnection filteredConnection(MBeanServer source) {
        return (MBeanServerConnection) Proxy.newProxyInstance(CamelHawtio.class.getClassLoader(),
            new Class<?>[] { MBeanServerConnection.class }, (proxy, method, args) -> {
                String action = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    return switch (action) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "Filtered local JMX connection";
                    };
                }
                if (action.equals("queryNames") || action.equals("queryMBeans")) {
                    @SuppressWarnings("unchecked")
                    Set<Object> matches = (Set<Object>) method.invoke(source, args);
                    matches.removeIf(item -> !visible(item instanceof ObjectName name ? name : ((javax.management.ObjectInstance) item).getObjectName()));
                    return matches;
                }
                if (args != null && args.length > 0 && args[0] instanceof ObjectName name) {
                    if (action.equals("isRegistered") && !visible(name)) return false;
                    if (!visible(name)) throw new javax.management.InstanceNotFoundException("MBean is outside the Camel management scope");
                    if (action.equals("getMBeanInfo")) {
                        var info = source.getMBeanInfo(name);
                        return new MBeanInfo(info.getClassName(), "Read-only inspection or scoped Camel management",
                            Arrays.stream(info.getAttributes()).filter(attribute -> readable(attribute.getName()) && attribute.isReadable())
                                .map(attribute -> new MBeanAttributeInfo(attribute.getName(), attribute.getType(), attribute.getDescription(), true, false, attribute.isIs()))
                                .toArray(MBeanAttributeInfo[]::new), null,
                            Arrays.stream(info.getOperations()).filter(operation -> allowedInvocation(source, name, operation.getName(),
                                Arrays.stream(operation.getSignature()).map(javax.management.MBeanParameterInfo::getType).toArray(String[]::new)))
                                .toArray(javax.management.MBeanOperationInfo[]::new), null);
                    }
                    if (action.equals("getAttribute") && !readable(String.valueOf(args[1]))) throw new SecurityException("Attribute is disabled");
                    if (action.equals("getAttributes")) {
                        args[1] = Arrays.stream((String[]) args[1]).filter(this::readable).toArray(String[]::new);
                    }
                    if (action.equals("invoke")) {
                        String[] signature = args[3] == null ? new String[0] : (String[]) args[3];
                        Object[] values = args[2] == null ? new Object[0] : (Object[]) args[2];
                        if (!allowedInvocation(source, name, String.valueOf(args[1]), signature) || values.length != signature.length
                            || Arrays.stream(values).anyMatch(value -> !(value instanceof Boolean))) throw new SecurityException("Operation is disabled");
                    }
                }
                if (!Set.of("getMBeanInfo", "getAttribute", "getAttributes", "invoke", "isRegistered", "isInstanceOf", "getObjectInstance",
                    "getDefaultDomain", "getDomains", "getMBeanCount").contains(action)) throw new SecurityException("JMX operation is disabled");
                try { return method.invoke(source, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
    }

    private boolean visible(ObjectName name) {
        return name != null && (jvmReadonly || ("org.apache.camel".equals(name.getDomain())
            && Set.of("context", "routes").contains(name.getKeyProperty("type"))));
    }

    private boolean readable(String attribute) { return jvmReadonly || ATTRIBUTES.contains(attribute); }

    // Review concrete Camel implementations, not annotations or a generic get*/dump* name heuristic.
    private boolean allowedInvocation(MBeanServer source, ObjectName name, String operation, String[] signature) {
        if (source != server) return false; // No exec on any Gateway/third-party MBean, including spoofed Camel names.
        if (signature.length == 0 && allowedControl(name, operation)) return true;
        if (!jvmReadonly || !"org.apache.camel".equals(name.getDomain())) return false;
        String kind = name.getKeyProperty("type");
        if ("context".equals(kind)) {
            if ("dumpRoutesAsXml".equals(operation) && signature.length == 0) return true;
            if ("dumpRoutesStatsAsXml".equals(operation) && Arrays.equals(signature, new String[] { "boolean", "boolean" })) return true;
        }
        if ("routes".equals(kind) && "isUpdateRouteEnabled".equals(operation) && signature.length == 0) return true;
        return Set.of("context", "routes", "processors").contains(kind)
            && "dumpStatsAsXml".equals(operation) && Arrays.equals(signature, new String[] { "boolean" });
    }

    private boolean inspectionOperation(ObjectName name, String operation) {
        if (!jvmReadonly || name == null || name.isPattern() || !server.isRegistered(name)) return false;
        for (String[] signature : List.of(new String[0], new String[] { "boolean" }, new String[] { "boolean", "boolean" })) {
            String bare = operation.contains("(") ? operation.substring(0, operation.indexOf('(')) : operation;
            if ((operation.equals(bare) || operation.equals(bare + "(" + String.join(",", signature) + ")"))
                && allowedInvocation(server, name, bare, signature)) return true;
        }
        return false;
    }

    private boolean allowedControl(ObjectName name, String operation) {
        return controlEnabled && visible(name) && "routes".equals(name.getKeyProperty("type"))
            && (CONTROLS.contains(operation) || CONTROLS.stream().anyMatch(control -> (control + "()").equals(operation)));
    }

    private final class Policy implements Restrictor {
        @Override public boolean isHttpMethodAllowed(HttpMethod method) { return method == HttpMethod.POST; }
        @Override public boolean isTypeAllowed(RequestType type) { return Set.of("version", "search", "list", "read", "exec").contains(type.getName()); }
        @Override public boolean isAttributeReadAllowed(ObjectName name, String attribute) { return visible(name) && readable(attribute); }
        @Override public boolean isAttributeWriteAllowed(ObjectName name, String attribute) { return false; }
        @Override public boolean isOperationAllowed(ObjectName name, String operation) { return allowedControl(name, operation) || inspectionOperation(name, operation); }
        @Override public boolean isRemoteAccessAllowed(String... addresses) { return true; }
        @Override public boolean isOriginAllowed(String origin, boolean strict) { return false; } // No cross-origin endpoint.
        @Override public boolean isObjectNameHidden(ObjectName name) { return !visible(name); }
    }

    public boolean controlEnabled() { return controlEnabled; }
    public String scope() { return jvmReadonly ? JVM_READONLY_SCOPE : MODULE_SCOPE; }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        // Called outside the runtime-registry monitor: an in-flight stop callback may use system.camel.
        admission.acquireUninterruptibly(4);
        try { manager.stop(); }
        finally {
            // Also clean failed-start remnants; this server owns no Gateway MBeans.
            for (ObjectName name : server.queryNames(null, null)) {
                if (name.getDomain().equals("JMImplementation")) continue;
                try { server.unregisterMBean(name); }
                catch (javax.management.JMException ignored) { /* Already unregistered by context shutdown. */ }
            }
            admission.release(4);
            synchronized (this) { connections.clear(); }
        }
    }

    public static final class BusyException extends IllegalStateException {
        public BusyException() { super("Camel management is busy"); }
    }
}
