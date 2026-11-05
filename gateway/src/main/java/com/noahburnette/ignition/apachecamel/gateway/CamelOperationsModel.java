package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.noahburnette.ignition.apachecamel.camel.CamelDependencyGraph;
import com.noahburnette.ignition.apachecamel.camel.CamelKameletResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Read-only configuration inventory. Source/credentials are hashed locally and never returned. */
final class CamelOperationsModel {
    private CamelOperationsModel() { }
    static CamelDependencyGraph graph(List<CamelConfigurationSnapshot> resources, Map<String, String> projects) {
        var nodes = new ArrayList<CamelDependencyGraph.Node>();
        var external = new LinkedHashMap<String, CamelDependencyGraph.Ref>();
        for (var resource : resources) {
            var refs = new ArrayList<CamelDependencyGraph.Ref>();
            var notes = new ArrayList<String>();
            var parameters = List.<Map<String, Object>>of();
            Object config = resource.config();
            if (config instanceof CamelRouteResource route) {
                route.components().forEach(name -> refs.add(ref("component", name, "Selected component")));
                route.registry().forEach(name -> refs.add(ref("registry", name, "Selected registry entry")));
                route.kamelets().forEach(name -> refs.add(ref("kamelet", name, "Selected Kamelet")));
                if ("JYTHON".equals(route.language())) { refs.add(ref("project", route.project(), "Jython route builder")); notes.add("Jython can create additional runtime dependencies; its code is not executed for inspection."); }
                else inspectSource(route.yaml(), refs, notes);
                route.secrets().values().forEach(secret -> secret(refs, secret));
            } else if (config instanceof CamelKameletResource kamelet) {
                kamelet.kamelets().forEach(name -> refs.add(ref("kamelet", name, "Nested Kamelet selection")));
                if ("JYTHON".equals(kamelet.language())) { refs.add(ref("project", kamelet.project(), "Jython template builder")); notes.add("Parameters and dynamic dependencies are declared in project code; the builder is not run to inspect them."); }
                else { inspectSource(kamelet.yaml(), refs, notes); parameters = parameters(kamelet.yaml()); }
            } else if (config instanceof CamelComponentResource component) {
                if (component.tlsReference() != null) refs.add(ref("registry", component.tlsReference(), "TLS configuration"));
                if (component.generic() != null) {
                    component.generic().references().values().forEach(name -> {
                        String database = IgnitionDatabaseRepository.databaseName(name);
                        refs.add(database == null ? ref("registry", name, "Generic component object reference")
                            : ref("database", database, "Native Ignition database connection"));
                    });
                    component.generic().secrets().values().forEach(secret -> secret(refs, secret));
                }
                if (component.mqtt() != null) secret(refs, component.mqtt().password());
            } else if (config instanceof CamelRegistryResource entry) {
                if (entry.bean() != null) { refs.add(ref("project", entry.bean().project(), "Bean factory project")); notes.add("A project bean can use additional resources; its factory is not run for inspection."); }
                if (entry.database() != null) refs.add(ref("database", entry.database(), "Ignition connection binding"));
                if (entry.tls() != null) for (var secret : new CamelSecretReference[]{entry.tls().trustStore(), entry.tls().trustStorePassword(), entry.tls().keyStore(), entry.tls().keyStorePassword(), entry.tls().privateKeyPassword()}) secret(refs, secret);
            }
            var uniqueRefs = refs.stream().distinct().toList();
            for (var ref : uniqueRefs) if (Set.of("project", "database", "secret").contains(ref.kind())) external.putIfAbsent(ref.key(), ref);
            nodes.add(new CamelDependencyGraph.Node(resource.kind(), resource.name(), resource.state(), resource.enabled(), false,
                fingerprint(Map.of("config", config, "enabled", resource.enabled())), uniqueRefs, notes, parameters));
        }
        for (var ref : external.values()) {
            String state = ref.kind().equals("project") ? projects.getOrDefault(ref.name(), "MISSING") : "VERIFY_EXTERNALLY";
            nodes.add(new CamelDependencyGraph.Node(ref.kind(), ref.name(), state, "AVAILABLE".equals(state), true, "", List.of(),
                ref.kind().equals("project") ? List.of("Project scripts/inheritance are separate deployment assets; an existing project name does not prove identical code.")
                    : List.of("Verify this external prerequisite on the target Gateway; no secret resolution, connection or script invocation was performed."), List.of()));
        }
        return new CamelDependencyGraph(nodes);
    }
    private static CamelDependencyGraph.Ref ref(String kind, String name, String reason) { return new CamelDependencyGraph.Ref(kind, name, reason); }
    private static void secret(List<CamelDependencyGraph.Ref> refs, CamelSecretReference secret) {
        if (secret != null) refs.add(ref("secret", secret.providerName() + "/" + secret.secretName(), "Native secret reference (not its value)"));
    }
    private static void inspectSource(String source, List<CamelDependencyGraph.Ref> refs, List<String> notes) {
        if (source == null) return;
        if (source.length() > 1048576) { notes.add("Source exceeds the static inspection bound; additional dependencies require manual review."); return; }
        if (source.contains(IgnitionDatabaseRepository.PREFIX))
            notes.add("Native database references resolve against Ignition Connections at runtime. Verify those connections separately; no database connection is opened for inspection.");
        var kamelets = Pattern.compile("\\bkamelet:([a-z][a-z0-9-]{0,63})(?=[/?\\s\"']|$)").matcher(source);
        while (kamelets.find()) {
            String name = kamelets.group(1);
            if (!name.equals("source") && !name.equals("sink") && refs.stream().noneMatch(ref -> ref.kind().equals("kamelet") && ref.name().equals(name)))
                refs.add(new CamelDependencyGraph.Ref("kamelet", name, "POSSIBLE", "Literal Kamelet URI; confirm its explicit selection"));
        }
        if (source.contains("ignition-script:") || source.contains("bean:") || source.contains("{{") || source.contains("${")
                || source.contains("toD:") || source.contains("process:") || source.contains("recipientList:"))
            notes.add("Script endpoints, expressions or dynamic endpoints may require additional projects/resources. Static inspection cannot prove their complete dependency set.");
    }
    private static List<Map<String, Object>> parameters(String yaml) {
        try {
            Object value = new Load(LoadSettings.builder().setCodePointLimit(262144).setMaxAliasesForCollections(32).build()).loadFromString(yaml);
            if (!(value instanceof Map<?, ?> root) || !(root.get("spec") instanceof Map<?, ?> spec)
                    || !(spec.get("definition") instanceof Map<?, ?> definition) || !(definition.get("properties") instanceof Map<?, ?> properties)) return List.of();
            var required = definition.get("required") instanceof List<?> names ? names : List.of();
            var result = new ArrayList<Map<String, Object>>();
            for (var entry : properties.entrySet()) if (entry.getKey() instanceof String name && entry.getValue() instanceof Map<?, ?> property) {
                Object type = property.get("type");
                result.add(Map.of("name", name, "type", type instanceof String text ? text : "unspecified",
                    "required", required.contains(name), "hasDefault", property.containsKey("default")));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) { return List.of(); }
    }
    static String fingerprint(Object value) {
        return digest(canonical(new Gson().toJsonTree(value)).toString());
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var object = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> object.add(entry.getKey(), canonical(entry.getValue())));
            return object;
        }
        if (value.isJsonArray()) {
            var array = new com.inductiveautomation.ignition.common.gson.JsonArray();
            value.getAsJsonArray().forEach(entry -> array.add(canonical(entry)));
            return array;
        }
        return value;
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
