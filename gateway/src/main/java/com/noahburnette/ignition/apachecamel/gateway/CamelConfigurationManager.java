package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.util.ResourceUtil;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.DefaultResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.actions.ResourceAction;
import com.inductiveautomation.ignition.gateway.config.actions.StatefulModificationResponse;
import com.inductiveautomation.ignition.gateway.config.actions.StructuralModificationResponse;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Native, independent component and registry resources; no shared live objects between contexts. */
public final class CamelConfigurationManager {
    public static final ResourceType COMPONENT = new ResourceType(IgnitionApacheCamelGatewayHook.MODULE_ID, "camel-component");
    public static final ResourceType REGISTRY = new ResourceType(IgnitionApacheCamelGatewayHook.MODULE_ID, "camel-registry");
    static ResourceTypeMeta<CamelComponentResource> componentMeta(com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions) {
        return new DefaultResourceTypeMeta.Builder<>(CamelComponentResource.class)
        .resourceType(COMPONENT).categoryName("Camel Components").description("Named component instances and shared endpoint defaults")
        .preferredResourceCollection("core").defaultConfig(new CamelComponentResource("HTTPS", new CamelComponentResource.Http(null, null, null), null, null))
        .buildValidator((config, errors) -> {
            config.validationErrors().forEach(errors::addFieldMessage);
            String scheme = switch (config.type()) { case "HTTP" -> "http"; case "HTTPS" -> "https"; case "MQTT5" -> "paho-mqtt5"; case "QUARTZ" -> "quartz"; default -> null; };
            if (scheme != null) try { extensions.schema(scheme); }
            catch (IllegalArgumentException unavailable) { errors.addFieldMessage("type", "Component library is not installed; upload it and restart the Gateway"); }
            if (config.type().equals("GENERIC") && config.generic() != null && config.generic().scheme() != null) {
                var generic = config.generic();
                try {
                    com.noahburnette.ignition.apachecamel.camel.CamelOptionMetadata.validate(extensions.schema(generic.scheme()), generic.options(), generic.secrets().keySet(), generic.references().keySet())
                        .forEach((name, message) -> errors.addFieldMessage("generic." + (generic.secrets().containsKey(name) ? "secrets" : generic.references().containsKey(name) ? "references" : "options") + "." + name, message));
                } catch (IllegalArgumentException missing) { errors.addFieldMessage("generic.scheme", missing.getMessage()); }
            }
        })
        .buildRouteDelegate(routes -> routes.openApiGroupName("Apache Camel").openApiTagName("camel-components").configSchema(CamelComponentResource.class))
        .buildEntityDelegate(entity -> entity.includeInSectionOverview(false).includeInDiagnosticsOverview(false))
        .buildActionSet(actions -> actions.create(componentCreate())
            .modify(componentModify()).rename(componentRename())).build();
    }
    public static final ResourceTypeMeta<CamelRegistryResource> REGISTRY_META = new DefaultResourceTypeMeta.Builder<>(CamelRegistryResource.class)
        .resourceType(REGISTRY).categoryName("Camel Registry").description("Named project beans, TLS configurations and Ignition database bindings")
        .preferredResourceCollection("core").defaultConfig(new CamelRegistryResource("BEAN", new CamelRegistryResource.Bean(null, null, null, true), null, null))
        .buildValidator((config, errors) -> config.validationErrors().forEach(errors::addFieldMessage))
        .buildRouteDelegate(routes -> routes.openApiGroupName("Apache Camel").openApiTagName("camel-registry").configSchema(CamelRegistryResource.class))
        .buildEntityDelegate(entity -> entity.includeInSectionOverview(false).includeInDiagnosticsOverview(false))
        .buildActionSet(actions -> actions.create(ResourceNames.validatingCreate("camel-registry")).rename(ResourceNames.validatingRename("camel-registry"))).build();

    private final GatewayContext gateway;
    private final com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions;
    private final Consumer<Set<String>> changed;
    private final NamedResourceHandler<CamelComponentResource> componentHandler;
    final ResourceTypeMeta<CamelComponentResource> componentMeta;
    private final NamedResourceHandler<CamelRegistryResource> registryHandler;
    private volatile Map<String, DecodedResource<CamelComponentResource>> components = Map.of();
    private volatile Map<String, DecodedResource<CamelRegistryResource>> registry = Map.of();

    CamelConfigurationManager(GatewayContext gateway, Consumer<Set<String>> changed, com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions) {
        this.gateway = gateway; this.changed = changed;
        this.extensions = extensions;
        componentMeta = componentMeta(extensions);
        componentHandler = NamedResourceHandler.newBuilder(componentMeta).context(gateway).filter(ResourceNames.filter("camel-component"))
            .onInitialOrUpdate(this::reconcileComponents).build();
        registryHandler = NamedResourceHandler.newBuilder(REGISTRY_META).context(gateway).filter(ResourceNames.filter("camel-registry"))
            .onInitialOrUpdate(this::reconcileRegistry).build();
    }
    private synchronized void reconcileComponents(List<DecodedResource<CamelComponentResource>> definitions) {
        var next = index(definitions);
        var differences = differences(components, next, "component:");
        components = next;
        if (!differences.isEmpty()) changed.accept(differences);
    }
    private synchronized void reconcileRegistry(List<DecodedResource<CamelRegistryResource>> definitions) {
        var next = index(definitions);
        var differences = differences(registry, next, "registry:");
        registry = next;
        if (!differences.isEmpty()) changed.accept(differences);
    }
    private static <T> Map<String, DecodedResource<T>> index(List<DecodedResource<T>> definitions) {
        var map = new HashMap<String, DecodedResource<T>>();
        definitions.forEach(value -> map.put(value.name(), value));
        return Map.copyOf(map);
    }
    private static <T> Set<String> differences(Map<String, DecodedResource<T>> before, Map<String, DecodedResource<T>> after, String prefix) {
        var names = new HashSet<>(before.keySet()); names.addAll(after.keySet());
        var gson = new Gson();
        names.removeIf(name -> before.containsKey(name) && after.containsKey(name)
            && ResourceUtil.isEnabled(before.get(name).resource()) == ResourceUtil.isEnabled(after.get(name).resource())
            && gson.toJsonTree(before.get(name).config()).equals(gson.toJsonTree(after.get(name).config())));
        return names.stream().map(prefix::concat).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public void startup() { registryHandler.startup(); componentHandler.startup(); }
    public void shutdown() { componentHandler.shutdown(); registryHandler.shutdown(); components = Map.of(); registry = Map.of(); }
    List<CamelConfigurationSnapshot> snapshots() {
        var result = new java.util.ArrayList<CamelConfigurationSnapshot>();
        components.values().forEach(value -> result.add(new CamelConfigurationSnapshot("component", value.name(), ResourceUtil.isEnabled(value.resource()),
            ResourceUtil.isEnabled(value.resource()) ? "CONFIGURED" : "DISABLED", value.config())));
        registry.values().forEach(value -> result.add(new CamelConfigurationSnapshot("registry", value.name(), ResourceUtil.isEnabled(value.resource()),
            ResourceUtil.isEnabled(value.resource()) ? "CONFIGURED" : "DISABLED", value.config())));
        return List.copyOf(result);
    }

    List<com.noahburnette.ignition.apachecamel.camel.CamelExtensionImpact.ComponentUse> extensionComponents() {
        return components.values().stream().sorted(java.util.Comparator.comparing(DecodedResource::name)).map(value -> {
            var config = value.config();
            String scheme = switch (config.type()) { case "HTTP" -> "http"; case "HTTPS" -> "https"; case "MQTT5" -> "paho-mqtt5"; case "QUARTZ" -> "quartz"; default -> config.generic() == null ? "" : config.generic().scheme(); };
            return new com.noahburnette.ignition.apachecamel.camel.CamelExtensionImpact.ComponentUse(value.name(), config.type(), scheme, ResourceUtil.isEnabled(value.resource()));
        }).toList();
    }
    boolean usesProjectFactory(CamelRouteResource route) {
        return registryNames(route).stream().map(registry::get).filter(java.util.Objects::nonNull).anyMatch(entry -> entry.config().type().equals("BEAN"));
    }
    String extensionRevision() {
        var values = new java.util.TreeMap<String, Object>();
        components.forEach((name, value) -> values.put("component:" + name, Map.of("config", value.config(), "enabled", ResourceUtil.isEnabled(value.resource()))));
        registry.forEach((name, value) -> values.put("registry:" + name, Map.of("config", value.config(), "enabled", ResourceUtil.isEnabled(value.resource()))));
        return new Gson().toJson(values);
    }

    private Set<String> registryNames(CamelRouteResource route) {
        var names = new LinkedHashSet<>(route.registry());
        for (String name : route.components()) {
            var component = components.get(name);
            if (component != null) names.addAll(component.config().registryReferences());
        }
        return names;
    }
    boolean affected(CamelRouteResource route, Set<String> changes) {
        return route.components().stream().anyMatch(name -> changes.contains("component:" + name))
            || registryNames(route).stream().anyMatch(name -> changes.contains("registry:" + name));
    }
    boolean dependsOnProject(CamelRouteResource route, String project) {
        return registryNames(route).stream().map(registry::get).filter(java.util.Objects::nonNull)
            .map(DecodedResource::config).anyMatch(value -> value.type().equals("BEAN") && value.bean() != null && project.equals(value.bean().project()));
    }
    CamelContextSetup bindings(CamelRouteResource route) {
        var selectedComponents = new LinkedHashMap<String, CamelComponentResource>();
        for (String component : route.components()) {
            if (!validComponentName(component)) throw new IllegalArgumentException("Invalid or reserved component URI scheme");
            selectedComponents.put(component, require(components, component, "Component"));
        }
        var selectedRegistry = new LinkedHashMap<String, CamelRegistryResource>();
        for (String entry : registryNames(route)) selectedRegistry.put(entry, require(registry, entry, "Registry entry"));
        for (var component : selectedComponents.values()) if (component.tlsReference() != null
            && !selectedRegistry.get(component.tlsReference()).type().equals("TLS")) throw new IllegalArgumentException("Component TLS reference must select a TLS registry entry");
        return new CamelContextSetup(gateway, route, selectedComponents, selectedRegistry, extensions);
    }
    private static <T> T require(Map<String, DecodedResource<T>> definitions, String name, String kind) {
        var resource = definitions.get(name);
        if (resource == null || !ResourceUtil.isEnabled(resource.resource())) throw new com.noahburnette.ignition.apachecamel.camel.CamelStartupFailure.UnavailableDependency(kind, name);
        return resource.config();
    }
    public void mount(RouteGroup routes) {
        routes.newRoute("/camel-configuration-options").handler((request, response) -> new Gson().toJsonTree(Map.of(
            "components", components.values().stream().map(value -> Map.of("name", value.name(), "type", value.config().type(),
                "scheme", value.config().generic() == null ? "" : value.config().generic().scheme(),
                "enabled", ResourceUtil.isEnabled(value.resource()), "registryReferences", value.config().registryReferences())).toList(),
            "registry", registry.values().stream().map(value -> Map.of("name", value.name(), "type", value.config().type(), "enabled", ResourceUtil.isEnabled(value.resource()))).toList())))
            .method(HttpMethod.GET).type(RouteGroup.TYPE_JSON).requirePermission(PermissionType.READ).nocache().mount();
    }
    static boolean validComponentName(String name) {
        return name != null && name.matches("[a-z][a-z0-9-]{0,63}") && !Set.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http", "platform-http", "properties").contains(name);
    }
    private static ResourceAction.Create componentCreate() {
        var delegate = ResourceNames.validatingCreate("camel-component");
        return (manager, requests, context) -> {
            if (requests.stream().anyMatch(request -> !validComponentName(request.path().getName()))) return new StatefulModificationResponse.Failure(new IllegalArgumentException("Component names are URI schemes: use 1–64 lowercase letters/digits/hyphens, starting with a letter; native Ignition schemes are reserved"));
            try {
                for (var request : requests) if (manager.getResource(request.path()).isPresent())
                    return new StatefulModificationResponse.Failure(new IllegalArgumentException("Component already exists"));
                return delegate.create(manager, requests, context);
            } catch (RuntimeException failure) { return new StatefulModificationResponse.Failure(failure); }
        };
    }
    private static ResourceAction.Modify componentModify() {
        var delegate = new com.inductiveautomation.ignition.gateway.config.actions.DefaultResourceActions.Modify();
        return (manager, requests, context) -> {
            try {
                for (var request : requests) {
                    var current = manager.getResource(request.path()).orElseThrow(() -> new IllegalArgumentException("Component no longer exists"));
                    if (request.signature() != null && !request.signature().equals(current.getContentDigest()))
                        return new StatefulModificationResponse.Failure(new IllegalStateException("Component changed; reload before saving"));
                }
                return delegate.modify(manager, requests, context);
            } catch (RuntimeException failure) { return new StatefulModificationResponse.Failure(failure); }
        };
    }
    private static ResourceAction.Rename componentRename() {
        var delegate = ResourceNames.validatingRename("camel-component");
        return (manager, type, oldName, newName, references, actor, context) -> {
            if (!validComponentName(newName)) return new StructuralModificationResponse.Failure(new IllegalArgumentException("Invalid or reserved component URI scheme"));
            return delegate.rename(manager, type, oldName, newName, references, actor, context);
        };
    }
}
