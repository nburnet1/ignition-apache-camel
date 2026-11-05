package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Native Camel parsing and deterministic, explicit transitive definition selection. */
public final class CamelKamelets {
    private CamelKamelets() { }
    private static final CamelValidationCache CACHE = new CamelValidationCache(128);
    public record Definition(String name, CamelKameletResource config, boolean enabled) { }

    public static void validate(CamelKameletResource config) throws Exception {
        var errors = config.validationErrors();
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.values().iterator().next());
        if ("JYTHON".equals(config.language())) return; // Never import user code to save/validate.
        CACHE.validate(config.yaml(), () -> {
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(CamelKamelets.class.getClassLoader());
                try (var context = CamelRouteRuntime.newValidationContext()) {
                    loadYaml(context, "validation-action", config.yaml());
                }
            } finally { thread.setContextClassLoader(previous); }
            return null;
        });
    }

    public static void loadYaml(DefaultCamelContext context, String name, String yaml) throws Exception {
        if (!CamelKameletResource.validName(name)) throw new IllegalArgumentException("Invalid Kamelet name");
        var loaded = new Load(LoadSettings.builder().setCodePointLimit(262144).setMaxAliasesForCollections(32).build()).loadFromString(yaml);
        if (!(loaded instanceof Map<?, ?> root) || !"Kamelet".equals(root.get("kind"))
                || !"camel.apache.org/v1".equals(root.get("apiVersion")) || !(root.get("spec") instanceof Map<?, ?>))
            throw new IllegalArgumentException("Use one apiVersion: camel.apache.org/v1, kind: Kamelet document");
        var document = new LinkedHashMap<Object, Object>(root);
        var metadata = root.get("metadata") instanceof Map<?, ?> map ? new LinkedHashMap<Object, Object>(map) : new LinkedHashMap<Object, Object>();
        metadata.put("name", name); // Native resource name is authoritative, including rename.
        document.put("metadata", metadata);
        if (context.getRouteTemplateDefinition(name) != null) throw new IllegalArgumentException("Duplicate template ID: " + name);
        int templates = context.getRouteTemplateDefinitions().size();
        int routes = context.getRouteDefinitions().size();
        var source = new Dump(DumpSettings.builder().build()).dumpToString(document);
        PluginHelper.getRoutesLoader(context).loadRoutes(ResourceHelper.fromString(name + ".kamelet.yaml", source));
        if (context.getRouteTemplateDefinitions().size() != templates + 1 || context.getRouteTemplateDefinition(name) == null
                || context.getRouteDefinitions().size() != routes)
            throw new IllegalArgumentException("A Kamelet must define exactly one template and no running routes");
        var route = context.getRouteTemplateDefinition(name).getRoute();
        if (route == null || route.getInput() == null || route.getInput().getEndpointUri() == null || route.getInput().getEndpointUri().isBlank())
            throw new IllegalArgumentException("Kamelet template.from.uri is required");
    }

    public static List<Definition> resolve(List<String> names, Map<String, Definition> definitions) {
        var ordered = new ArrayList<Definition>();
        var visiting = new LinkedHashSet<String>();
        var visited = new LinkedHashSet<String>();
        for (String name : names) visit(name, definitions, visiting, visited, ordered);
        return List.copyOf(ordered);
    }
    private static void visit(String name, Map<String, Definition> definitions, Set<String> visiting,
            Set<String> visited, List<Definition> ordered) {
        if (visited.contains(name)) return;
        if (visiting.size() >= 64 || visited.size() + visiting.size() >= 128)
            throw new IllegalArgumentException("Kamelet selection exceeds 128 definitions or 64 nesting levels");
        if (!visiting.add(name)) throw new IllegalArgumentException("Cyclic Kamelet dependencies: " + name);
        var definition = definitions.get(name);
        if (definition == null || !definition.enabled()) throw new CamelStartupFailure.UnavailableDependency("Kamelet", name);
        for (String dependency : definition.config().kamelets()) visit(dependency, definitions, visiting, visited, ordered);
        visiting.remove(name);
        visited.add(name);
        ordered.add(definition);
    }
    /** Includes missing/disabled names, so deleting or disabling a dependency rebuilds its callers. */
    public static Set<String> references(List<String> names, Map<String, Definition> definitions) {
        var result = new LinkedHashSet<String>();
        var pending = new java.util.ArrayDeque<>(names);
        while (!pending.isEmpty()) {
            var name = pending.removeFirst();
            if (!result.add(name)) continue;
            var definition = definitions.get(name);
            if (definition != null) pending.addAll(definition.config().kamelets());
        }
        return Set.copyOf(result);
    }
}
