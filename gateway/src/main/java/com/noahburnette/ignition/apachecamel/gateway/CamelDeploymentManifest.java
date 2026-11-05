package com.noahburnette.ignition.apachecamel.gateway;

import com.noahburnette.ignition.apachecamel.camel.CamelDependencyGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A portable inventory/lock manifest, not a configuration archive or an importer. */
final class CamelDeploymentManifest {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    record Library(String name, String sha256, long bytes, String state, String origin, String artifact, String version) { }
    record Manifest(int schemaVersion, String moduleId, String moduleVersion, String camelVersion, String minimumIgnitionVersion, long createdAt,
                    List<CamelDependencyGraph.Node> resources, List<Library> libraries, List<String> notes) { }
    static Manifest create(CamelDependencyGraph graph, List<Map<String, Object>> inventory) {
        var libraries = inventory.stream().map(row -> new Library(row.get("name").toString(), row.get("sha256").toString(),
            ((Number) row.get("bytes")).longValue(), row.get("state").toString(), row.get("origin").toString(),
            row.getOrDefault("artifact", "").toString(), row.getOrDefault("version", "").toString()))
            .sorted(java.util.Comparator.comparing(Library::name).thenComparing(Library::sha256)).toList();
        return new Manifest(1, IgnitionApacheCamelGatewayHook.MODULE_ID, "unknown",
            com.noahburnette.ignition.apachecamel.camel.CamelComponentCatalog.VERSION, "8.3.8", System.currentTimeMillis(), graph.nodes(), libraries,
            List.of("Fingerprints cover active/effective configurations and enablement, not resource backup/override files or project code.",
                "This is the complete Gateway extension inventory, not a minimal per-route library dependency set.",
                "Module versions may not distinguish development builds; verify the intended signed release artifact separately.",
                "Move definitions/projects with native Ignition exports/backups and retain matching trusted JARs separately. No secret values or configuration source are included.",
                "Database contents, certificates, secret-provider values, project inheritance and dynamic references require separate verification. No code, endpoint, secret or database was invoked to build this manifest."));
    }
    static Manifest versioned(Manifest manifest, String version) {
        return new Manifest(manifest.schemaVersion(), manifest.moduleId(), version, manifest.camelVersion(), manifest.minimumIgnitionVersion(),
            manifest.createdAt(), manifest.resources(), manifest.libraries(), manifest.notes());
    }
    static Map<String, Object> compare(Manifest expected, Manifest current) {
        var projects = current.resources().stream().filter(node -> node.kind().equals("project"))
            .collect(java.util.stream.Collectors.toMap(CamelDependencyGraph.Node::name, CamelDependencyGraph.Node::state));
        return compare(expected, current, projects);
    }
    static Map<String, Object> compare(Manifest expected, Manifest current, Map<String, String> projects) {
        validate(expected);
        var errors = new ArrayList<Map<String, String>>();
        var warnings = new ArrayList<Map<String, String>>();
        if (!expected.moduleId().equals(current.moduleId())) errors.add(row("MODULE", "module", "Module IDs differ"));
        if (expected.moduleVersion().equals("unknown") || current.moduleVersion().equals("unknown")) warnings.add(row("MODULE_VERSION_UNKNOWN", "module", "Verify the installed module release/build manually"));
        else if (!expected.moduleVersion().equals(current.moduleVersion())) errors.add(row("MODULE_VERSION", "module", "Module versions differ"));
        if (!expected.camelVersion().equals(current.camelVersion())) errors.add(row("CAMEL_VERSION", "runtime", "Camel engine versions differ"));
        if (!expected.minimumIgnitionVersion().equals(current.minimumIgnitionVersion())) warnings.add(row("IGNITION_REQUIREMENT", "runtime", "Minimum Ignition requirements differ; verify the target runtime manually"));
        var actualLibraries = current.libraries().stream().collect(java.util.stream.Collectors.groupingBy(Library::name));
        for (var library : expected.libraries()) {
            if (!"ACTIVE".equals(library.state())) warnings.add(row("SOURCE_PENDING", library.name(), "Source library was not ACTIVE when exported; resolve staged/removal/blocked states before deployment"));
            var matches = actualLibraries.getOrDefault(library.name(), List.of());
            if (matches.isEmpty()) errors.add(row("MISSING_LIBRARY", library.name(), "Required inventory library is missing"));
            else if (matches.size() != 1 || !matches.get(0).sha256().equals(library.sha256()) || matches.get(0).bytes() != library.bytes())
                errors.add(row("LIBRARY_MISMATCH", library.name(), "Library checksum/size differs or multiple copies exist"));
            else if (!"ACTIVE".equals(matches.get(0).state())) errors.add(row("LIBRARY_NOT_ACTIVE", library.name(), "Target library is staged, blocked or pending removal"));
        }
        var expectedNames = expected.libraries().stream().map(Library::name).collect(java.util.stream.Collectors.toSet());
        for (var library : current.libraries()) if (!expectedNames.contains(library.name())) warnings.add(row("EXTRA_LIBRARY", library.name(), "Additional target library; review compatibility rather than deleting it automatically"));
        var actualResources = current.resources().stream().collect(java.util.stream.Collectors.toMap(CamelDependencyGraph.Node::key, value -> value));
        for (var resource : expected.resources()) {
            var actual = actualResources.get(resource.key());
            if (resource.external()) {
                if (resource.kind().equals("project") && "AVAILABLE".equals(resource.state()) && !"AVAILABLE".equals(projects.get(resource.name())))
                    errors.add(row("PROJECT_UNAVAILABLE", resource.name(), "Required runnable project is missing, disabled or inheritable-only on target"));
                if (resource.kind().equals("project") && !"AVAILABLE".equals(resource.state()))
                    warnings.add(row("SOURCE_PROJECT_UNAVAILABLE", resource.name(), "Project prerequisite was already unavailable on the source; resolve or review before deployment"));
                warnings.add(row("EXTERNAL_PREREQUISITE", resource.kind() + ":" + resource.name(), "Verify external asset values/content separately; names alone do not prove a matching deployment"));
            } else if (actual == null) errors.add(row("MISSING_RESOURCE", resource.key(), "Native definition is missing"));
            else if (!actual.fingerprint().equals(resource.fingerprint())) errors.add(row("CONFIGURATION_MISMATCH", resource.key(), "Active/effective configuration or enablement differs"));
            else if ("ERROR".equals(actual.state())) warnings.add(row("RESOURCE_ERROR", resource.key(), "Definition matches but the target runtime reports ERROR"));
            if (!resource.uncertainties().isEmpty()) warnings.add(row("STATIC_UNCERTAINTY", resource.key(), "Static dependencies are incomplete; inspect dynamic code, properties and project inheritance manually"));
        }
        var expectedKeys = expected.resources().stream().map(CamelDependencyGraph.Node::key).collect(java.util.stream.Collectors.toSet());
        for (var resource : current.resources()) if (!resource.external() && !expectedKeys.contains(resource.key())) warnings.add(row("EXTRA_RESOURCE", resource.key(), "Additional target definition; it will not be changed"));
        return Map.of("blockingMismatch", !errors.isEmpty(), "errors", List.copyOf(errors), "warnings", List.copyOf(warnings),
            "note", "Read-only comparison only. This does not import resources, install libraries, resolve secrets or certify end-to-end readiness.");
    }
    private static Map<String, String> row(String code, String name, String message) { return Map.of("code", code, "name", name, "message", message); }
    private static void validate(Manifest value) {
        if (value == null || value.schemaVersion() != 1 || value.moduleId() == null || value.moduleId().length() > 256
                || value.moduleVersion() == null || value.moduleVersion().length() > 128
                || value.camelVersion() == null || value.camelVersion().length() > 80 || value.minimumIgnitionVersion() == null
                || value.minimumIgnitionVersion().length() > 80 || value.resources() == null || value.resources().size() > 10000
                || value.libraries() == null || value.libraries().size() > 128) throw new IllegalArgumentException("Unsupported or oversized deployment manifest");
        var keys = new HashSet<String>();
        for (var node : value.resources()) {
            if (node == null || node.kind() == null || !Set.of("route", "component", "registry", "kamelet", "profile", "project", "database", "secret").contains(node.kind())
                    || node.name() == null || node.name().isBlank() || node.name().length() > 512 || !keys.add(node.key())
                    || node.external() != Set.of("project", "database", "secret").contains(node.kind())
                    || node.fingerprint() == null || !node.external() && !node.fingerprint().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid or duplicate manifest resource");
        }
        keys.clear();
        for (var library : value.libraries()) if (library == null || library.name() == null || !library.name().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.jar")
                || library.sha256() == null || !library.sha256().matches("[a-f0-9]{64}") || library.bytes() < 0 || !keys.add(library.name() + ":" + library.sha256()))
            throw new IllegalArgumentException("Invalid or duplicate manifest library");
    }
}
