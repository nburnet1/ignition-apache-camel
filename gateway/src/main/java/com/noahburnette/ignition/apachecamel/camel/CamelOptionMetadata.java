package com.noahburnette.ignition.apachecamel.camel;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Validate metadata without constructing components, resolving secrets or contacting services. */
public final class CamelOptionMetadata {
    private CamelOptionMetadata() { }
    public static Map<String, String> validate(JsonObject schema, Map<String, String> options,
        Set<String> secrets, Set<String> references) {
        var errors = new LinkedHashMap<String, String>();
        var properties = schema.getAsJsonObject("componentProperties");
        if (properties == null) properties = new JsonObject();
        var names = new java.util.HashSet<>(options.keySet()); names.addAll(secrets); names.addAll(references);
        for (String name : names) {
            if (!properties.has(name)) { errors.put(name, "Unknown component option"); continue; }
            var spec = properties.getAsJsonObject(name);
            if (spec.has("deprecated") && spec.get("deprecated").getAsBoolean()) { errors.put(name, "Deprecated component option is not supported"); continue; }
            if ((options.containsKey(name) ? 1 : 0) + (secrets.contains(name) ? 1 : 0) + (references.contains(name) ? 1 : 0) != 1) errors.put(name, "Supply one value, secret or registry reference");
            if (spec.has("secret") && spec.get("secret").getAsBoolean() && !secrets.contains(name)) errors.put(name, "Use a secret-provider reference");
            String type = spec.has("type") ? spec.get("type").getAsString() : "object";
            if ("object".equals(type) && !references.contains(name)) errors.put(name, "Use a registry reference for this Java object");
            if (references.contains(name) && !"object".equals(type)) errors.put(name, "This option does not accept an object reference");
            if (!options.containsKey(name)) continue;
            String value = options.get(name);
            if (value == null) { errors.put(name, "Supply a value or omit the option"); continue; }
            if (value.contains("{{")) continue;
            try {
                if ("boolean".equals(type) && !Set.of("true", "false").contains(value)) throw new IllegalArgumentException();
                if ("integer".equals(type)) new BigDecimal(value).toBigIntegerExact();
                if ("number".equals(type)) new BigDecimal(value);
                if (spec.has("enum") && java.util.stream.StreamSupport.stream(spec.getAsJsonArray("enum").spliterator(), false).noneMatch(item -> item.getAsString().equals(value))) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { errors.put(name, "Choose a valid " + type + " value"); }
            if (spec.has("insecureValue") && value.equals(spec.get("insecureValue").getAsString())) errors.put(name, "Insecure TLS bypasses are not supported");
        }
        for (var entry : properties.entrySet()) {
            var spec = entry.getValue().getAsJsonObject();
            if (spec.has("deprecated") && spec.get("deprecated").getAsBoolean()) continue;
            if (spec.has("required") && spec.get("required").getAsBoolean() && !spec.has("defaultValue")
                && (!names.contains(entry.getKey()) || (options.containsKey(entry.getKey()) && (options.get(entry.getKey()) == null || options.get(entry.getKey()).isBlank())))) errors.put(entry.getKey(), "Required component option");
        }
        return errors;
    }
}
