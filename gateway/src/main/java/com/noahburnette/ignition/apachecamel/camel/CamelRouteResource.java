package com.noahburnette.ignition.apachecamel.camel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;

/** Native Camel authoring; identity and enabled state belong to Ignition's resource.json. */
public record CamelRouteResource(String language, String yaml, String project, String functionPath,
    List<String> components, List<String> registry, Map<String, String> properties, Map<String, CamelSecretReference> secrets,
    List<String> httpSecurityLevels, List<String> kamelets) {
    public CamelRouteResource {
        language = language == null ? "YAML" : language;
        project = project == null ? null : project.trim();
        functionPath = functionPath == null ? null : functionPath.trim();
        components = components == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(components));
        registry = registry == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(registry));
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        secrets = secrets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(secrets));
        httpSecurityLevels = httpSecurityLevels == null ? List.of()
            : Collections.unmodifiableList(new java.util.ArrayList<>(httpSecurityLevels));
        kamelets = kamelets == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(kamelets));
    }

    public CamelRouteResource(String language, String yaml, String project, String functionPath,
        List<String> components, List<String> registry, Map<String, String> properties, Map<String, CamelSecretReference> secrets,
        List<String> httpSecurityLevels) {
        this(language, yaml, project, functionPath, components, registry, properties, secrets, httpSecurityLevels, null);
    }

    public CamelRouteResource(String language, String yaml, String project, String functionPath,
        List<String> components, List<String> registry, Map<String, String> properties, Map<String, CamelSecretReference> secrets) {
        this(language, yaml, project, functionPath, components, registry, properties, secrets, null);
    }

    public CamelRouteResource(String language, String yaml, String project, String functionPath) {
        this(language, yaml, project, functionPath, null, null, null, null);
    }

    /** Convenience for YAML route definitions. */
    public CamelRouteResource(String yaml) {
        this("YAML", yaml, null, null);
    }

    public String sourceField() {
        return "JYTHON".equals(language) ? "functionPath" : "yaml";
    }

    public Map<String, String> validationErrors() {
        Map<String, String> errors = new LinkedHashMap<>();
        validateNames(components, "components", errors);
        validateNames(registry, "registry", errors);
        validateNames(kamelets, "kamelets", errors);
        if (httpSecurityLevels.size() > 32 || httpSecurityLevels.stream().anyMatch(path -> !validHttpSecurityLevel(path))
            || httpSecurityLevels.stream().distinct().count() != httpSecurityLevels.size()) {
            errors.put("httpSecurityLevels", "Use up to 32 distinct security-level paths under Authenticated, e.g. Authenticated/Integrations/OrdersSubmit");
        }
        if (properties.size() > 64 || properties.entrySet().stream().anyMatch(entry -> !propertyName(entry.getKey())
            || entry.getValue() == null || entry.getValue().length() > 4096 || entry.getKey().startsWith("secret."))) {
            errors.put("properties", "Use at most 64 named string properties; secret.* is reserved");
        }
        if (secrets.size() > 32 || secrets.entrySet().stream().anyMatch(entry -> !propertyName(entry.getKey())
            || entry.getValue() == null || !entry.getValue().isValid())) errors.put("secrets", "Use at most 32 named provider/secret references");
        if (!"YAML".equals(language) && !"JYTHON".equals(language)) {
            errors.put("language", "language must be YAML or JYTHON");
        } else if ("YAML".equals(language)) {
            if (yaml == null || yaml.isBlank()) errors.put("yaml", "yaml must contain a Camel route definition");
        } else {
            if (project == null || project.isBlank()) errors.put("project", "project is required for a Jython route");
            if (functionPath == null || !functionPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                errors.put("functionPath", "Set a project-library function such as camel_routes.orders");
            }
        }
        return errors;
    }

    private static boolean propertyName(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"); }
    private static boolean validHttpSecurityLevel(String path) {
        if (path == null || path.length() > 1024 || !path.startsWith("Authenticated/")) return false;
        String[] segments = path.split("/", -1);
        return segments.length <= 16 && java.util.Arrays.stream(segments)
            .allMatch(segment -> !segment.isBlank() && segment.equals(segment.trim())
                && segment.length() <= 128 && segment.chars().noneMatch(Character::isISOControl));
    }
    private static void validateNames(List<String> names, String field, Map<String, String> errors) {
        if (names.size() > 32 || names.stream().anyMatch(name -> name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            || names.stream().distinct().count() != names.size()) errors.put(field, "Select up to 32 distinct named resources");
    }

    public void requireSource() {
        var errors = validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.values().iterator().next());
        }
    }
}
