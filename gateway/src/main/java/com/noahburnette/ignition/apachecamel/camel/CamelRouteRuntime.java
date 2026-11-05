package com.noahburnette.ignition.apachecamel.camel;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.engine.ExplicitCamelContextNameStrategy;
import org.apache.camel.spi.RoutesLoader;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;

/**
 * One isolated route resource. Camel owns execution and error handling; this class
 * only owns its lifecycle and module class-loader boundary. YAML is privileged
 * Gateway configuration, not a sandbox for untrusted expressions or endpoints.
 */
public final class CamelRouteRuntime implements AutoCloseable {
    private static final CamelValidationCache VALIDATION_CACHE = new CamelValidationCache(128);
    private final DefaultCamelContext context;
    private final ProducerTemplate producer;
    private final boolean jython;
    private final CamelRouteStatistics statistics = new CamelRouteStatistics();
    private final AutoCloseable ownedSetup;
    private final ClassLoader runtimeLoader;

    public CamelRouteRuntime(String name, CamelRouteResource resource) throws Exception {
        this(name, resource, context -> { });
    }

    public CamelRouteRuntime(
        String name, CamelRouteResource resource, Consumer<CamelContext> configure
    ) throws Exception {
        this(name, resource, configure, null);
    }

    public CamelRouteRuntime(
        String name, CamelRouteResource resource, Consumer<CamelContext> configure,
        JythonRoutes.Configurer projectFunction
    ) throws Exception {
        this(name, resource, configure, projectFunction, () -> { });
    }

    public CamelRouteRuntime(String name, CamelRouteResource resource, Consumer<CamelContext> configure,
        JythonRoutes.Configurer projectFunction, AutoCloseable ownedSetup) throws Exception {
        this(name, resource, configure, projectFunction, ownedSetup, CamelRouteRuntime.class.getClassLoader());
    }

    public CamelRouteRuntime(String name, CamelRouteResource resource, Consumer<CamelContext> configure,
        JythonRoutes.Configurer projectFunction, AutoCloseable ownedSetup, ClassLoader runtimeLoader) throws Exception {
        this(name, resource, configure, projectFunction, ownedSetup, runtimeLoader, false);
    }

    public CamelRouteRuntime(String name, CamelRouteResource resource, Consumer<CamelContext> configure,
        JythonRoutes.Configurer projectFunction, AutoCloseable ownedSetup, ClassLoader runtimeLoader, boolean jythonDefinitions) throws Exception {
        this.ownedSetup = Objects.requireNonNull(ownedSetup);
        this.runtimeLoader = Objects.requireNonNull(runtimeLoader);
        Objects.requireNonNull(resource, "resource");
        jython = "JYTHON".equals(resource.language()) || jythonDefinitions;
        context = newContext(name);
        context.setApplicationContextClassLoader(runtimeLoader);
        context.getClassResolver().addClassLoader(runtimeLoader);
        try {
            producer = inRuntime(() -> {
                statistics.install(context);
                configure.accept(context);
                load(context, resource, projectFunction);
                context.start();
                return context.createProducerTemplate();
            });
        } catch (Exception | Error error) {
            try {
                inRuntime(() -> {
                    try { context.stop(); } finally { ownedSetup.close(); }
                    return null;
                });
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private static DefaultCamelContext newContext(String name) {
        var context = new DefaultCamelContext();
        context.setNameStrategy(new ExplicitCamelContextNameStrategy("integration-" + name));
        context.setApplicationContextClassLoader(CamelRouteRuntime.class.getClassLoader());
        context.disableJMX();
        context.getShutdownStrategy().setTimeout(5);
        context.getShutdownStrategy().setShutdownNowOnTimeout(true);
        return context;
    }

    /** Validate YAML models or a project reference without executing user code during save. */
    public static void validate(CamelRouteResource resource) throws Exception {
        resource.requireSource();
        if ("JYTHON".equals(resource.language())) {
            return;
        }
        VALIDATION_CACHE.validate(resource.yaml(), () -> withModuleLoader(() -> {
            validateYaml(resource.yaml());
            return null;
        }));
    }

    /** Construct Camel model definitions, but never apply their executable customizers. */
    private static void validateYaml(String yaml) throws Exception {
        var settings = org.snakeyaml.engine.v2.api.LoadSettings.builder()
            .setCodePointLimit(262144).setMaxAliasesForCollections(32).build();
        var root = new org.snakeyaml.engine.v2.api.lowlevel.Compose(settings).composeString(yaml)
            .orElseThrow(() -> new IllegalArgumentException("yaml must define at least one Camel route"));
        if (!(root instanceof org.snakeyaml.engine.v2.nodes.SequenceNode definitions))
            throw new IllegalArgumentException("yaml must be a list of Camel definitions");
        try (var context = newValidationContext()) {
            var parser = new org.apache.camel.dsl.yaml.common.YamlDeserializationContext(settings);
            parser.setCamelContext(context);
            parser.setResource(ResourceHelper.fromString("config.camel.yaml", yaml));
            parser.setCompactNotationWarn(false);
            // Use only Camel's built-in model constructors, without discovering user resolvers.
            parser.addBuiltinResolver(new org.apache.camel.dsl.yaml.deserializers.CustomResolver(
                new org.apache.camel.dsl.yaml.deserializers.BeansDeserializer()));
            parser.addBuiltinResolver(new org.apache.camel.dsl.yaml.deserializers.ModelDeserializersResolver());
            boolean hasRoute = false;
            for (var node : definitions.getValue()) {
                Object model = parser.mandatoryResolve(node).construct(node);
                org.apache.camel.model.FromDefinition input;
                if (model instanceof org.apache.camel.model.RouteDefinition route) input = route.getInput();
                else if (model instanceof org.apache.camel.dsl.yaml.deserializers.OutputAwareFromDefinition from)
                    input = from.getDelegate();
                else continue; // Bean/customizer definitions are parsed, never configured.
                if (input == null || input.getEndpointUri() == null || input.getEndpointUri().isBlank())
                    throw new IllegalArgumentException("route.from.uri is required");
                hasRoute = true;
            }
            if (!hasRoute) throw new IllegalArgumentException("yaml must define at least one Camel route");
        }
    }

    static DefaultCamelContext newValidationContext() {
        var context = newContext("validation");
        context.setStartupSummaryLevel(org.apache.camel.StartupSummaryLevel.Off);
        var properties = new java.util.Properties();
        properties.setProperty("camel.main.yamlDslCompactNotationWarn", "false");
        context.getPropertiesComponent().setInitialProperties(properties);
        return context;
    }

    private static void load(
        DefaultCamelContext context, CamelRouteResource resource, JythonRoutes.Configurer projectFunction
    ) throws Exception {
        resource.requireSource();
        if ("JYTHON".equals(resource.language())) {
            JythonRoutes.load(context, projectFunction);
        } else {
            RoutesLoader loader = PluginHelper.getRoutesLoader(context);
            loader.loadRoutes(ResourceHelper.fromString("config.camel.yaml", resource.yaml()));
        }
        if (context.getRouteDefinitions().isEmpty()) {
            throw new IllegalArgumentException(resource.sourceField() + " must define at least one Camel route");
        }
    }

    /** Synchronous ingress for Java integrations; does not add a second retry policy. */
    public Object request(String endpoint, Object body) throws Exception {
        return request(endpoint, body, Map.of());
    }

    public Object request(String endpoint, Object body, Map<String, Object> headers) throws Exception {
        return inRuntime(() -> producer.requestBodyAndHeaders(endpoint, body, headers));
    }

    /** InOnly is synchronous submission, not an asynchronous/durable delivery promise. */
    public void send(String endpoint, Object body, Map<String, Object> headers) throws Exception {
        inRuntime(() -> { producer.sendBodyAndHeaders(endpoint, body, headers); return null; });
    }

    public boolean isStarted() {
        return context.isStarted();
    }

    public CamelRouteStatistics statistics() { return statistics; }

    @Override
    public void close() throws Exception {
        inRuntime(() -> {
            try {
                producer.stop();
            } finally {
                try { context.stop(); } finally { ownedSetup.close(); }
            }
            return null;
        });
    }

    private <T> T inRuntime(Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(runtimeLoader);
            return jython ? JythonRoutes.preservingCallerState(action) : action.call();
        } finally { thread.setContextClassLoader(previous); }
    }

    private static <T> T withModuleLoader(Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(CamelRouteRuntime.class.getClassLoader());
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
