package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.util.ResourceUtil;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.DefaultResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import com.noahburnette.ignition.apachecamel.camel.CamelStartupFailure;
import com.noahburnette.ignition.apachecamel.camel.IgnitionComponents;
import com.noahburnette.ignition.apachecamel.camel.IgnitionServices;
import com.noahburnette.ignition.apachecamel.camel.IgnitionPlatformHttpEngine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.python.core.Py;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ignition remains the persistence boundary; Camel owns the executable route model. */
public final class CamelRouteResourceManager implements com.noahburnette.ignition.apachecamel.camel.CamelRouteAccess, CamelRouteMetrics.Source {
    private static final Logger LOGGER = LoggerFactory.getLogger(CamelRouteResourceManager.class);
    public static final ResourceType RESOURCE_TYPE = new ResourceType(
        IgnitionApacheCamelGatewayHook.MODULE_ID, "camel-route"
    );
    public static final ResourceTypeMeta<CamelRouteResource> META =
        new DefaultResourceTypeMeta.Builder<>(CamelRouteResource.class)
            .withCodec(new CamelRouteCodec())
            .resourceType(RESOURCE_TYPE)
            .categoryName("Camel Routes")
            .description("Named Apache Camel routes authored in YAML or Jython DSL")
            .preferredResourceCollection("core")
            .defaultConfig(new CamelRouteResource("""
                - route:
                    id: example
                    from:
                      uri: direct:example
                      steps:
                        - setBody:
                            simple: 'Hello ${body}'
                """))
            .buildRouteDelegate(routes -> routes
                .openApiGroupName("Apache Camel")
                .openApiTagName("camel-routes")
                .supportsExtraDataFiles(true)
                .configSchema(CamelRouteResource.class))
            .buildEntityDelegate(entity -> entity
                .includeInSectionOverview(false)
                .includeInDiagnosticsOverview(false))
            .buildActionSet(actions -> actions
                .create(CamelRouteCodec.createAction())
                .modify(CamelRouteCodec.modifyAction())
                .rename(ResourceNames.validatingRename("camel-route")))
            .build();

    private final NamedResourceHandler<CamelRouteResource> handler;
    private final IgnitionServices services;
    private final GatewayContext context;
    private final CamelProjectLifecycle projects;
    private final CamelRouteMetrics metrics;
    private final CamelConfigurationManager configurations;
    private final CamelKameletManager kamelets;
    private final com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions;
    private final com.noahburnette.ignition.apachecamel.camel.CamelHawtio hawtio;
    private final ExecutorService reloadWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "camel-project-reload");
        thread.setDaemon(true);
        thread.setContextClassLoader(CamelRouteResourceManager.class.getClassLoader());
        return thread;
    });
    private final Set<String> pendingProjects = new HashSet<>();
    private final Set<String> pendingDependencies = new HashSet<>(); // guarded by pendingProjects
    private final AtomicBoolean reloadScheduled = new AtomicBoolean();
    private final Map<String, RunningRoute> running = new HashMap<>();
    private final com.noahburnette.ignition.apachecamel.camel.CamelHttpPathRegistry httpPaths =
        new com.noahburnette.ignition.apachecamel.camel.CamelHttpPathRegistry();
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, CamelStartupFailure> startupErrors = new HashMap<>();
    private final Set<String> queuedRestarts = new HashSet<>();
    private List<DecodedResource<CamelRouteResource>> definitions = List.of();
    private volatile boolean started;
    private volatile boolean stopped;
    private long generation;

    public CamelRouteResourceManager(GatewayContext context) {
        this.context = context;
        try {
            extensions = new com.noahburnette.ignition.apachecamel.camel.CamelExtensions(
                context.getSystemManager().getDataDir().toPath().resolve("apache-camel/camel-extensions"), getClass().getClassLoader());
        } catch (java.io.IOException error) { throw new IllegalStateException("Could not initialize Camel extension directory", error); }
        services = new GatewayCamelServices(context);
        projects = new CamelProjectLifecycle(context.getProjectManager(), this::requestProjectReload);
        configurations = new CamelConfigurationManager(context, this::requestDependencyReload, extensions);
        kamelets = new CamelKameletManager(context, this::requestDependencyReload);
        metrics = new CamelRouteMetrics(context.getMetricRegistry(), context.getHealthCheckRegistry(), this);
        handler = NamedResourceHandler.newBuilder(META)
            .context(context)
            .filter(ResourceNames.filter("camel-route"))
            .onInitialOrUpdate(this::reconcile)
            .build();
        hawtio = Boolean.getBoolean(com.noahburnette.ignition.apachecamel.camel.CamelHawtio.ENABLED_PROPERTY)
            ? new com.noahburnette.ignition.apachecamel.camel.CamelHawtio(
                Boolean.getBoolean(com.noahburnette.ignition.apachecamel.camel.CamelHawtio.CONTROL_PROPERTY),
                System.getProperty(com.noahburnette.ignition.apachecamel.camel.CamelHawtio.SCOPE_PROPERTY,
                    com.noahburnette.ignition.apachecamel.camel.CamelHawtio.MODULE_SCOPE)) : null;
    }

    public void startup() {
        CamelIntegrationServlet.register(context, this);
        configurations.startup();
        kamelets.startup();
        // Ignore the watcher's baseline startup notifications; the handler loads those definitions next.
        projects.startup();
        started = true;
        handler.startup();
    }

    private synchronized void reconcile(List<DecodedResource<CamelRouteResource>> resources) {
        definitions = List.copyOf(resources);
        reconcile(resources, Set.of());
    }

    private synchronized void reconcile(
        List<DecodedResource<CamelRouteResource>> resources, Set<String> changedProjects
    ) {
        reconcile(resources, changedProjects, null);
    }

    private synchronized void reconcile(
        List<DecodedResource<CamelRouteResource>> resources, Set<String> changedProjects, String restartName
    ) {
        reconcile(resources, changedProjects, restartName, Set.of());
    }

    private synchronized void reconcile(List<DecodedResource<CamelRouteResource>> resources,
        Set<String> changedProjects, String restartName, Set<String> changedDependencies) {
        if (stopped) {
            return;
        }
        // Release removed/renamed resources before starting replacements, so their
        // HTTP consumers cannot falsely block the replacement's identical path.
        var currentNames = resources.stream().map(DecodedResource::name).collect(java.util.stream.Collectors.toSet());
        for (String name : List.copyOf(running.keySet())) {
            if (!currentNames.contains(name)) stop(name);
        }
        var retained = new HashSet<String>();
        for (DecodedResource<CamelRouteResource> decoded : resources) {
            String name = decoded.name();
            retained.add(name);
            if (restartName != null && !restartName.equals(name)) continue;
            RunningRoute existing = running.get(name);
            boolean enabled = ResourceUtil.isEnabled(decoded.resource());
            boolean projectChanged = ("JYTHON".equals(decoded.config().language())
                && decoded.config().project() != null && changedProjects.contains(decoded.config().project()))
                || changedProjects.stream().anyMatch(project -> configurations.dependsOnProject(decoded.config(), project)
                    || kamelets.dependsOnProject(decoded.config(), project));
            boolean dependencyChanged = configurations.affected(decoded.config(), changedDependencies) || kamelets.affected(decoded.config(), changedDependencies);
            if ((!changedProjects.isEmpty() || !changedDependencies.isEmpty()) && !projectChanged && !dependencyChanged) continue;
            if (enabled && existing != null && existing.resource().equals(decoded.config()) && !projectChanged
                && !dependencyChanged && restartName == null) {
                continue;
            }
            stop(name);
            startupErrors.remove(name);
            states.put(name, "DISABLED");
            if (enabled) {
                try {
                    var components = new IgnitionComponents(services);
                    var http = new IgnitionPlatformHttpEngine(16, httpPaths, name);
                    var config = decoded.config();
                    var setup = configurations.bindings(config);
                    var templates = kamelets.selection(config);
                    running.put(name, new RunningRoute(
                        config, new CamelRouteRuntime(name, config, camel -> {
                            if (hawtio != null) hawtio.install(camel);
                            components.install(camel);
                            http.install(camel);
                            setup.install(camel);
                            templates.install((org.apache.camel.impl.DefaultCamelContext) camel);
                        }, builder -> {
                            var manifest = context.getProjectManager().getManifests().get(config.project());
                            if (manifest == null || !manifest.enabled() || manifest.inheritable()) {
                                throw new IllegalStateException("Route project must exist, be enabled and runnable: " + config.project());
                            }
                            GatewayScriptFunctions.route(context, config.project(), config.functionPath())
                                .invoke(Py.java2py(builder));
                        }, setup, extensions.loader(), templates.usesJython()), components, http, ++generation
                    ));
                    states.put(name, "RUNNING");
                    LOGGER.info("Started Camel route resource {}", name);
                } catch (Exception | LinkageError error) {
                    states.put(name, "ERROR");
                    startupErrors.put(name, CamelStartupFailure.from(error));
                    // A bad definition must not stop reconciliation of unrelated routes.
                    LOGGER.error("Could not start Camel route resource {}", name, error);
                }
            }
        }
        for (String name : List.copyOf(running.keySet())) {
            if (!retained.contains(name)) {
                stop(name);
            }
        }
        states.keySet().retainAll(retained);
        startupErrors.keySet().retainAll(retained);
        metrics.reconcile();
    }

    private void requestProjectReload(String project) {
        if (!started || stopped) return;
        synchronized (pendingProjects) {
            pendingProjects.add(project);
        }
        if (reloadScheduled.compareAndSet(false, true)) {
            try {
                reloadWorker.execute(this::reloadProjects);
            } catch (java.util.concurrent.RejectedExecutionException error) {
                reloadScheduled.set(false);
                if (!stopped) LOGGER.warn("Could not schedule Camel project reload", error);
            }
        }
    }

    private void requestDependencyReload(Set<String> names) {
        if (!started || stopped) return;
        synchronized (pendingProjects) { pendingDependencies.addAll(names); }
        // Schedule through the same coalescing worker without manufacturing a project change.
        scheduleReload();
    }

    private void scheduleReload() {
        if (reloadScheduled.compareAndSet(false, true)) {
            try { reloadWorker.execute(this::reloadProjects); }
            catch (java.util.concurrent.RejectedExecutionException failure) { reloadScheduled.set(false); }
        }
    }

    private void reloadProjects() {
        try {
            while (!stopped) {
                Set<String> changed;
                Set<String> changedDependencies;
                synchronized (pendingProjects) {
                    if (pendingProjects.isEmpty() && pendingDependencies.isEmpty()) break;
                    changed = Set.copyOf(pendingProjects);
                    changedDependencies = Set.copyOf(pendingDependencies);
                    pendingProjects.clear();
                    pendingDependencies.clear();
                }
                synchronized (this) {
                    reconcile(definitions, changed, null, changedDependencies);
                }
            }
        } finally {
            reloadScheduled.set(false);
            synchronized (pendingProjects) {
                if (!stopped && (!pendingProjects.isEmpty() || !pendingDependencies.isEmpty())) scheduleReload();
            }
        }
    }

    public void mount(RouteGroup routes) {
        if (hawtio != null) new CamelHawtioRoutes(hawtio).mount(routes);
        configurations.mount(routes);
        kamelets.mount(routes);
        new CamelOperationsRoutes(context, this, extensions).mount(routes);
        CamelExtensionRoutes.mount(routes, extensions, this);
        routes.newRoute("/camel-components")
            .handler((request, response) -> new Gson().toJsonTree(Map.of(
                "camelVersion", com.noahburnette.ignition.apachecamel.camel.CamelComponentCatalog.VERSION,
                "entries", com.noahburnette.ignition.apachecamel.camel.CamelComponentCatalog.entries())))
            .type(RouteGroup.TYPE_JSON).method(HttpMethod.GET).requirePermission(PermissionType.READ).nocache().mount();
        routes.newRoute("/camel-route-details")
            .handler((request, response) -> {
                try { return new Gson().toJsonTree(routeStatus(request.getParameter("name"))); }
                catch (IllegalArgumentException missing) {
                    response.setStatus(404);
                    return new Gson().toJsonTree(Map.of("error", "Unknown Camel resource"));
                }
            })
            .type(RouteGroup.TYPE_JSON).method(HttpMethod.GET).requirePermission(PermissionType.READ).nocache().mount();
        routes.newRoute("/camel-route-restart")
            .handler((request, response) -> {
                try {
                    restart(request.getParameter("name"));
                    response.setStatus(202);
                    return new Gson().toJsonTree(Map.of("queued", true));
                } catch (IllegalArgumentException | IllegalStateException unavailable) {
                    response.setStatus(409);
                    return new Gson().toJsonTree(Map.of("error", "Resource is missing, disabled or stopped"));
                }
            })
            .type(RouteGroup.TYPE_JSON).method(HttpMethod.POST).requirePermission(PermissionType.WRITE).nocache().mount();
        routes.newRoute("/camel-routes")
            .handler((request, response) -> new Gson().toJsonTree(status()))
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .nocache()
            .mount();
        routes.newRoute("/camel-route-diagnostics")
            .handler((request, response) -> new Gson().toJsonTree(diagnostics()))
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .nocache()
            .mount();
    }

    public synchronized Map<String, String> status() {
        return Map.copyOf(states);
    }

    public synchronized List<String> list() {
        return states.keySet().stream().sorted().toList();
    }

    public synchronized Map<String, Object> routeStatus(String name) {
        if (!states.containsKey(name)) throw new IllegalArgumentException("Unknown Camel resource: " + name);
        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        result.put("state", states.get(name));
        result.put("diagnostics", diagnostics().getOrDefault(name, Map.of()));
        var startupFailure = startupErrors.get(name);
        result.put("startupError", startupFailure == null ? "" : startupFailure.title());
        result.put("startupFailure", startupFailure == null ? Map.of() : startupFailure.snapshot());
        result.put("hawtioEnabled", hawtio != null);
        result.put("hawtioControlEnabled", hawtio != null && hawtio.controlEnabled());
        result.put("hawtioScope", hawtio == null ? "disabled" : hawtio.scope());
        definitions.stream().filter(definition -> definition.name().equals(name)).findFirst().ifPresent(definition -> {
            result.put("enabled", ResourceUtil.isEnabled(definition.resource()));
            result.put("description", definition.description());
            result.put("language", definition.config().language());
            result.put("components", definition.config().components());
            result.put("registry", definition.config().registry());
            result.put("kamelets", definition.config().kamelets());
            result.put("httpSecurityLevels", definition.config().httpSecurityLevels());
            if ("JYTHON".equals(definition.config().language())) {
                result.put("project", definition.config().project());
                result.put("functionPath", definition.config().functionPath());
            }
        });
        var runtime = running.get(name);
        var httpEndpoints = new java.util.ArrayList<Map<String, String>>();
        if (runtime != null) {
            for (String path : runtime.http().registeredPaths()) {
                httpEndpoints.add(Map.of("method", "POST", "path",
                    com.noahburnette.ignition.apachecamel.camel.CamelHttpPublicPaths.PREFIX + path));
            }
        }
        result.put("httpEndpoints", List.copyOf(httpEndpoints));
        result.put("routes", runtime == null ? List.of() : runtime.runtime().statistics().snapshot());
        result.put("metrics", runtime == null ? Map.of() : runtime.runtime().statistics().aggregate());
        var routeStatistics = runtime == null ? List.<Map<String, Object>>of() : runtime.runtime().statistics().snapshot();
        result.put("recentFailures", runtime == null ? List.of() : runtime.runtime().statistics().recentFailures());
        result.put("diagnosticSummary", com.noahburnette.ignition.apachecamel.camel.CamelDiagnostics.summarize(
            states.get(name), routeStatistics, diagnostics().getOrDefault(name, Map.of()), startupFailure));
        result.put("startedAt", runtime == null ? 0 : runtime.runtime().statistics().createdAt());
        return result;
    }

    public synchronized List<String> routeIds(String name) {
        var route = running.get(name);
        return route == null ? List.of() : route.runtime().statistics().routeIds();
    }

    public synchronized Number metric(String name, String routeId, String key) {
        var route = running.get(name);
        if (route == null) return 0;
        var stats = route.runtime().statistics();
        return routeId == null ? stats.aggregateMetric(key) : stats.metric(routeId, key);
    }

    private synchronized CamelRouteRuntime requireRuntime(String name) {
        if (!states.containsKey(name)) throw new IllegalArgumentException("Unknown Camel resource: " + name);
        var route = running.get(name);
        if (stopped || route == null) throw new IllegalStateException("Camel resource is not running: " + name);
        return route.runtime();
    }

    public Object request(String name, String endpoint, Object body, Map<String, Object> headers) throws Exception {
        // Never hold the registry monitor while executing user routes/callbacks.
        return requireRuntime(name).request(endpoint, body, headers);
    }

    /** Capture the selected runtime and policy together; delivery never holds this monitor. */
    synchronized HttpTarget httpTarget(String name, String path) throws IgnitionPlatformHttpEngine.HttpFailure {
        if (!states.containsKey(name)) throw new IgnitionPlatformHttpEngine.HttpFailure(404, "Unknown Camel resource");
        var route = running.get(name);
        if (stopped || route == null) throw new IgnitionPlatformHttpEngine.HttpFailure(503, "Camel resource is not running");
        return new HttpTarget(name, path, route.resource(), route.http());
    }

    synchronized HttpTarget publicHttpTarget(String publicPath) throws IgnitionPlatformHttpEngine.HttpFailure {
        if (stopped) throw new IgnitionPlatformHttpEngine.HttpFailure(503, "HTTP integration is unavailable");
        var owner = httpPaths.owner(publicPath);
        if (owner == null) throw new IgnitionPlatformHttpEngine.HttpFailure(404, "Unknown HTTP integration path");
        return httpTarget(owner, publicPath);
    }

    record HttpTarget(String name, String path, CamelRouteResource resource, IgnitionPlatformHttpEngine http) { }

    public void send(String name, String endpoint, Object body, Map<String, Object> headers) throws Exception {
        requireRuntime(name).send(endpoint, body, headers);
    }

    public synchronized void restart(String name) {
        var definition = definitions.stream().filter(resource -> resource.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Camel resource: " + name));
        if (stopped || !ResourceUtil.isEnabled(definition.resource())) {
            throw new IllegalStateException("Camel resource is disabled or stopped: " + name);
        }
        // Async so a Processor may request a restart without trying to stop its own exchange.
        if (!queuedRestarts.add(name)) return;
        try {
            reloadWorker.execute(() -> {
                synchronized (this) {
                    try { reconcile(definitions, Set.of(), name); }
                    finally { queuedRestarts.remove(name); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            queuedRestarts.remove(name);
            throw new IllegalStateException("Camel reload worker is stopped", error);
        }
    }

    public synchronized Map<String, Map<String, Long>> diagnostics() {
        Map<String, Map<String, Long>> result = new HashMap<>();
        running.forEach((name, route) -> {
            Map<String, Long> metrics = new HashMap<>(route.components().status());
            metrics.putAll(route.http().status());
            metrics.put("generation", route.generation());
            result.put(name, metrics);
        });
        return result;
    }

    public void shutdown() {
        stopped = true;
        CamelIntegrationServlet.unregister(context, this);
        // Quiesce external controls before stopping contexts, without holding the registry monitor.
        if (hawtio != null) hawtio.close();
        projects.shutdown();
        configurations.shutdown();
        kamelets.shutdown();
        reloadWorker.shutdownNow();
        synchronized (pendingProjects) {
            pendingProjects.clear();
            pendingDependencies.clear();
        }
        handler.shutdown();
        services.close();
        synchronized (this) {
            stopped = true;
            for (String name : List.copyOf(running.keySet())) {
                stop(name);
            }
            states.clear();
            startupErrors.clear();
            metrics.close();
            definitions = List.of();
            queuedRestarts.clear();
            try { extensions.close(); } catch (java.io.IOException error) { LOGGER.warn("Could not close Camel extension loader", error); }
        }
    }

    ClassLoader extensionLoader() { return extensions.loader(); }
    synchronized List<CamelConfigurationSnapshot> configurationSnapshots() {
        var result = new java.util.ArrayList<CamelConfigurationSnapshot>();
        definitions.forEach(value -> result.add(new CamelConfigurationSnapshot("route", value.name(), ResourceUtil.isEnabled(value.resource()),
            states.getOrDefault(value.name(), "UNKNOWN"), value.config())));
        result.addAll(configurations.snapshots());
        result.addAll(kamelets.snapshots());
        return List.copyOf(result);
    }
    synchronized com.noahburnette.ignition.apachecamel.camel.CamelExtensionImpact.Result extensionImpact(
        com.noahburnette.ignition.apachecamel.camel.CamelExtensionGraph.Removal removal) {
        var inputs = definitions.stream().sorted(java.util.Comparator.comparing(DecodedResource::name)).map(value -> {
            var config = value.config();
            return new com.noahburnette.ignition.apachecamel.camel.CamelExtensionImpact.RouteInput(value.name(), states.getOrDefault(value.name(), "DISABLED"),
                ResourceUtil.isEnabled(value.resource()), config.language(), kamelets.impactSource(config), config.components(),
                configurations.usesProjectFactory(config) || !config.kamelets().isEmpty());
        }).toList();
        return com.noahburnette.ignition.apachecamel.camel.CamelExtensionImpact.analyze(configurations.extensionComponents(), inputs,
            removal.schemes(), removal.features(), removal.schemes().isEmpty() && removal.features().isEmpty());
    }
    synchronized String extensionRevision() {
        var values = new java.util.TreeMap<String, Object>();
        definitions.forEach(value -> values.put(value.name(), Map.of("config", value.config(), "enabled", ResourceUtil.isEnabled(value.resource()))));
        return new Gson().toJson(values) + configurations.extensionRevision() + kamelets.extensionRevision();
    }
    ResourceTypeMeta<CamelComponentResource> componentMeta() { return configurations.componentMeta; }

    private void stop(String name) {
        RunningRoute route = running.remove(name);
        if (route != null) {
            try {
                route.runtime().close();
            } catch (Exception error) {
                LOGGER.error("Could not stop Camel route resource {}", name, error);
            }
        }
    }

    private record RunningRoute(
        CamelRouteResource resource, CamelRouteRuntime runtime, IgnitionComponents components,
        IgnitionPlatformHttpEngine http, long generation
    ) { }
}
