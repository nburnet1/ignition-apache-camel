package com.noahburnette.ignition.apachecamel.camel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A reusable definition, not a running context. Identity belongs to the native resource. */
public record CamelKameletResource(String language, String yaml, String project, String functionPath, List<String> kamelets) {
    public CamelKameletResource {
        language = language == null ? "YAML" : language;
        project = project == null ? null : project.trim();
        functionPath = functionPath == null ? null : functionPath.trim();
        kamelets = kamelets == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(kamelets));
    }
    public String sourceField() { return "JYTHON".equals(language) ? "functionPath" : "yaml"; }
    public static boolean validName(String name) {
        return name != null && name.matches("[a-z][a-z0-9-]{0,63}") && !List.of("source", "sink").contains(name);
    }
    public Map<String, String> validationErrors() {
        var errors = new LinkedHashMap<String, String>();
        if (!List.of("YAML", "JYTHON").contains(language)) errors.put("language", "Select YAML or JYTHON");
        else if ("YAML".equals(language)) {
            if (yaml == null || yaml.isBlank() || yaml.length() > 262144) errors.put("yaml", "Provide a Kamelet YAML definition up to 256 KiB");
        } else {
            if (project == null || project.isBlank()) errors.put("project", "Select an enabled, runnable project");
            if (functionPath == null || !functionPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
                errors.put("functionPath", "Use a project-library function such as kamelets.temperature");
        }
        if (kamelets.size() > 32 || kamelets.stream().anyMatch(name -> !validName(name)) || kamelets.stream().distinct().count() != kamelets.size())
            errors.put("kamelets", "Select up to 32 distinct Kamelet definitions");
        return errors;
    }
}
