package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.util.ResourceUtil;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.DefaultResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.noahburnette.ignition.apachecamel.camel.CamelKameletResource;
import com.noahburnette.ignition.apachecamel.camel.CamelKamelets;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.JythonKamelets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.camel.impl.DefaultCamelContext;
import org.python.core.Py;

/** Definition snapshots only. Each consuming context receives its own fresh template models. */
public final class CamelKameletManager {
    public static final ResourceType TYPE = new ResourceType(IgnitionApacheCamelGatewayHook.MODULE_ID, "camel-kamelet");
    public static final String DEFAULT_YAML = """
        apiVersion: camel.apache.org/v1
        kind: Kamelet
        metadata:
          name: greeting-action
          labels:
            camel.apache.org/kamelet.type: action
        spec:
          definition:
            title: Greeting
            properties:
              prefix:
                type: string
                default: "Hello "
          template:
            from:
              uri: kamelet:source
              steps:
                - setBody:
                    simple: '{{prefix}}${body}'
                - to: kamelet:sink
        """;
    public static final ResourceTypeMeta<CamelKameletResource> META = new DefaultResourceTypeMeta.Builder<>(CamelKameletResource.class)
        .withCodec(new CamelKameletCodec()).resourceType(TYPE).categoryName("Camel Kamelets")
        .description("Reusable YAML or project-library Jython integration templates")
        .preferredResourceCollection("core").defaultConfig(new CamelKameletResource("YAML", DEFAULT_YAML, null, null, null))
        .buildRouteDelegate(routes -> routes.openApiGroupName("Apache Camel").openApiTagName("camel-kamelets")
            .supportsExtraDataFiles(true).configSchema(CamelKameletResource.class))
        .buildEntityDelegate(entity -> entity.includeInSectionOverview(false).includeInDiagnosticsOverview(false))
        .buildActionSet(actions -> actions.create(CamelKameletCodec.createAction()).modify(CamelKameletCodec.modifyAction())
            .rename(CamelKameletCodec.renameAction())).build();

    private final GatewayContext gateway;
    private final Consumer<Set<String>> changed;
    private final NamedResourceHandler<CamelKameletResource> handler;
    private volatile Map<String, CamelKamelets.Definition> definitions = Map.of();

    CamelKameletManager(GatewayContext gateway, Consumer<Set<String>> changed) {
        this.gateway = gateway;
        this.changed = changed;
        handler = NamedResourceHandler.newBuilder(META).context(gateway).filter(ResourceNames.filter("camel-kamelet"))
            .onInitialOrUpdate(this::reconcile).build();
    }
    private synchronized void reconcile(List<DecodedResource<CamelKameletResource>> resources) {
        var next = new HashMap<String, CamelKamelets.Definition>();
        for (var resource : resources) next.put(resource.name(), new CamelKamelets.Definition(resource.name(), resource.config(), ResourceUtil.isEnabled(resource.resource())));
        var before = definitions;
        var differences = new HashSet<>(before.keySet()); differences.addAll(next.keySet());
        differences.removeIf(name -> java.util.Objects.equals(before.get(name), next.get(name)));
        definitions = Map.copyOf(next);
        if (!differences.isEmpty()) changed.accept(differences.stream().map(name -> "kamelet:" + name).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
    void startup() { handler.startup(); }
    void shutdown() { handler.shutdown(); definitions = Map.of(); }
    List<CamelConfigurationSnapshot> snapshots() {
        return definitions.values().stream().map(value -> new CamelConfigurationSnapshot("kamelet", value.name(), value.enabled(),
            value.enabled() ? "CONFIGURED" : "DISABLED", value.config())).toList();
    }
    void mount(com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup routes) {
        routes.newRoute("/camel-kamelets").handler((request, response) -> new com.inductiveautomation.ignition.common.gson.Gson()
            .toJsonTree(Map.of("definitions", options())))
            .method(com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod.GET)
            .type(com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup.TYPE_JSON)
            .requirePermission(com.inductiveautomation.ignition.gateway.dataroutes.PermissionType.READ).nocache().mount();
    }
    List<Map<String, Object>> options() {
        return definitions.values().stream().sorted(java.util.Comparator.comparing(CamelKamelets.Definition::name))
            .map(value -> Map.<String, Object>of("name", value.name(), "type", value.config().language(), "enabled", value.enabled())).toList();
    }
    boolean affected(CamelRouteResource route, Set<String> changes) {
        return CamelKamelets.references(route.kamelets(), definitions).stream().anyMatch(name -> changes.contains("kamelet:" + name));
    }
    boolean dependsOnProject(CamelRouteResource route, String project) {
        var snapshot = definitions;
        return CamelKamelets.references(route.kamelets(), snapshot).stream().map(snapshot::get).filter(java.util.Objects::nonNull)
            .anyMatch(value -> "JYTHON".equals(value.config().language()) && project.equals(value.config().project()));
    }
    Selection selection(CamelRouteResource route) { return new Selection(CamelKamelets.resolve(route.kamelets(), definitions)); }
    String extensionRevision() { return new com.inductiveautomation.ignition.common.gson.Gson().toJson(new java.util.TreeMap<>(definitions)); }
    String impactSource(CamelRouteResource route) {
        var source = new StringBuilder(route.yaml() == null ? "" : route.yaml());
        var snapshot = definitions;
        for (String name : CamelKamelets.references(route.kamelets(), snapshot)) {
            var definition = snapshot.get(name);
            if (definition != null && "YAML".equals(definition.config().language())) source.append('\n').append(definition.config().yaml());
        }
        return source.toString();
    }
    final class Selection {
        private final List<CamelKamelets.Definition> selected;
        Selection(List<CamelKamelets.Definition> selected) { this.selected = selected; }
        boolean usesJython() { return selected.stream().anyMatch(value -> "JYTHON".equals(value.config().language())); }
        void install(DefaultCamelContext context) {
            try {
                if (!selected.isEmpty()) context.getComponent("kamelet"); // Explicitly fail if the managed library was removed.
                for (var definition : selected) {
                    var config = definition.config();
                    if ("YAML".equals(config.language())) CamelKamelets.loadYaml(context, definition.name(), config.yaml());
                    else {
                        var manifest = gateway.getProjectManager().getManifests().get(config.project());
                        if (manifest == null || !manifest.enabled() || manifest.inheritable()) throw new IllegalStateException("Kamelet project must be enabled and runnable");
                        JythonKamelets.load(context, definition.name(), builder -> GatewayScriptFunctions.route(gateway,
                            config.project(), config.functionPath()).invoke(Py.java2py(builder)));
                    }
                }
            } catch (Exception failure) { throw new IllegalStateException("Could not load selected Kamelet definitions", failure); }
        }
    }
}
