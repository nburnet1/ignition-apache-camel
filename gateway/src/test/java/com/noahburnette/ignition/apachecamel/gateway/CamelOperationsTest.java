package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.noahburnette.ignition.apachecamel.camel.CamelDependencyGraph;
import com.noahburnette.ignition.apachecamel.camel.CamelKameletResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamelOperationsTest {
    @Test void nativeDatasourceReferencesAreExternalPrerequisitesRatherThanMissingAliases() {
        var component = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("jdbc", Map.of(), Map.of(),
                Map.of("dataSource", "ignition.database/ERP Main")));
        var graph = CamelOperationsModel.graph(List.of(snapshot("component", "orders", component)), Map.of());
        var required = rows(graph.view("component", "orders"), "requires");
        assertEquals(1, required.size());
        assertEquals("database", required.get(0).get("kind"));
        assertEquals("ERP Main", required.get(0).get("name"));
        assertEquals("VERIFY_EXTERNALLY", required.get(0).get("state"));
    }
    @Test void dottedDatabaseAliasesKeepTheirAliasAndTargetDependencies() {
        var component = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("jdbc", Map.of(), Map.of(),
                Map.of("dataSource", "ignition.database.Reporting")));
        var alias = new CamelRegistryResource("DATABASE", null, null, "Production");
        var graph = CamelOperationsModel.graph(List.of(snapshot("component", "orders", component),
            snapshot("registry", "ignition.database.Reporting", alias)), Map.of());
        var required = rows(graph.view("component", "orders"), "requires");
        assertTrue(required.stream().anyMatch(row -> row.get("kind").equals("registry")
            && row.get("name").equals("ignition.database.Reporting") && row.get("relationship").equals("Direct")));
        assertTrue(required.stream().anyMatch(row -> row.get("kind").equals("database")
            && row.get("name").equals("Production") && row.get("relationship").equals("Transitive")));
        assertFalse(required.stream().anyMatch(row -> row.get("name").equals("Reporting")));
    }
    private CamelConfigurationSnapshot snapshot(String kind, String name, Object config) {
        return new CamelConfigurationSnapshot(kind, name, true, kind.equals("route") ? "RUNNING" : "CONFIGURED", config);
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> view, String key) { return (List<Map<String, Object>>) view.get(key); }
    private static final String SOURCE = """
        apiVersion: camel.apache.org/v1
        kind: Kamelet
        metadata:
          name: ignored
        spec:
          definition:
            title: Private title
            required: [field]
            properties:
              field:
                type: string
                default: never-export-this-default
          template:
            from:
              uri: kamelet:source
              steps:
                - to: kamelet:sink
        """;

    @Test void dependencyDetailsIncludeTransitiveTlsAndNestedCallersWithoutSourcesOrValues() {
        var route = new CamelRouteResource("YAML", "- route: private-source-value", null, null, List.of("broker"), List.of("factory"),
            Map.of("value", "private-property-value"), Map.of("token", new CamelSecretReference("provider", "token")), List.of(), List.of("parent"));
        var tls = new CamelRegistryResource("TLS", null, new CamelRegistryResource.Tls("PKCS12", "TLS", new CamelSecretReference("provider", "trust"),
            new CamelSecretReference("provider", "password"), null, null, null), null);
        var broker = new CamelComponentResource("MQTT5", null,
            new CamelComponentResource.Mqtt("tcp://private-host", "private-user", null, "tls", 1, true, 10, 0L), null);
        var factory = new CamelRegistryResource("BEAN", new CamelRegistryResource.Bean("Project", "factories.make", Map.of("arg", "private-factory-argument"), true), null, null);
        var graph = CamelOperationsModel.graph(List.of(snapshot("route", "consumer", route), snapshot("component", "broker", broker), snapshot("registry", "tls", tls),
            snapshot("registry", "factory", factory), snapshot("kamelet", "parent", new CamelKameletResource("YAML", SOURCE, null, null, List.of("leaf"))),
            snapshot("kamelet", "leaf", new CamelKameletResource("YAML", SOURCE, null, null, null))), Map.of("Project", "AVAILABLE"));
        var view = graph.view("route", "consumer");
        assertTrue(rows(view, "requires").stream().anyMatch(row -> row.get("kind").equals("registry") && row.get("name").equals("tls") && row.get("relationship").equals("Transitive")));
        assertTrue(rows(graph.view("kamelet", "leaf"), "usedBy").stream().anyMatch(row -> row.get("name").equals("consumer") && row.get("relationship").equals("Transitive")));
        assertEquals(Boolean.TRUE, graph.nodes().stream().filter(node -> node.kind().equals("kamelet")).findFirst().orElseThrow().parameters().get(0).get("hasDefault"));
        String json = new Gson().toJson(graph.nodes());
        for (String privateValue : List.of("private-source-value", "private-property-value", "private-factory-argument", "private-host", "private-user", "never-export-this-default", "factories.make"))
            assertFalse(json.contains(privateValue), privateValue);
        assertTrue(json.contains("provider/trust")); // Native reference name, never a resolved value.
    }

    @Test void missingDisabledCyclesAndDynamicUncertaintyAreVisibleWithoutRunningCode() {
        var script = new CamelKameletResource("JYTHON", null, "Missing", "danger.neverExecute", List.of("gone"));
        var graph = CamelOperationsModel.graph(List.of(snapshot("kamelet", "script", script)), Map.of());
        var view = graph.view("kamelet", "script");
        assertTrue(rows(view, "requires").stream().anyMatch(row -> row.get("name").equals("gone") && row.get("state").equals("MISSING")));
        assertTrue(rows(view, "requires").stream().anyMatch(row -> row.get("name").equals("Missing") && row.get("state").equals("MISSING")));
        assertFalse(((List<?>) view.get("uncertainties")).isEmpty());
        var a = new CamelDependencyGraph.Node("kamelet", "a", "DISABLED", false, false, "a".repeat(64), List.of(new CamelDependencyGraph.Ref("kamelet", "b", "nested")), List.of(), List.of());
        var b = new CamelDependencyGraph.Node("kamelet", "b", "CONFIGURED", true, false, "b".repeat(64), List.of(new CamelDependencyGraph.Ref("kamelet", "a", "nested")), List.of(), List.of());
        assertEquals(1, rows(new CamelDependencyGraph(List.of(a, b)).view("kamelet", "b"), "requires").size());
    }

    @Test void canonicalFingerprintsAreOrderIndependentButDetectConfigurationChanges() {
        assertEquals(CamelOperationsModel.fingerprint(Map.of("a", 1, "b", 2)), CamelOperationsModel.fingerprint(new java.util.LinkedHashMap<>(Map.of("b", 2, "a", 1))));
        assertNotEquals(CamelOperationsModel.fingerprint(Map.of("a", 1)), CamelOperationsModel.fingerprint(Map.of("a", 2)));
    }

    private CamelDeploymentManifest.Manifest manifest(String digest, String state) {
        var graph = CamelOperationsModel.graph(List.of(snapshot("kamelet", "one", new CamelKameletResource("YAML", SOURCE, null, null, null))), Map.of());
        return CamelDeploymentManifest.create(graph, List.of(Map.of("name", "camel-kamelet-4.22.2.jar", "sha256", digest, "bytes", 100L, "state", state, "origin", "INCLUDED")));
    }
    @Test void manifestRoundTripsAndComparisonReportsChecksumsMissingResourcesAndInactiveLibraries() {
        var original = manifest("a".repeat(64), "ACTIVE");
        var json = new Gson().toJson(original);
        assertFalse(json.contains("never-export-this-default"));
        assertFalse(json.contains("kind: Kamelet"));
        var imported = new Gson().fromJson(json, CamelDeploymentManifest.Manifest.class);
        assertEquals(false, CamelDeploymentManifest.compare(imported, original).get("blockingMismatch"));
        var changed = CamelDeploymentManifest.compare(imported, manifest("b".repeat(64), "ACTIVE"));
        assertEquals(true, changed.get("blockingMismatch"));
        assertTrue(changed.toString().contains("LIBRARY_MISMATCH"));
        assertTrue(CamelDeploymentManifest.compare(imported, manifest("a".repeat(64), "RESTART_REQUIRED")).toString().contains("LIBRARY_NOT_ACTIVE"));
        var missing = new CamelDeploymentManifest.Manifest(1, original.moduleId(), original.moduleVersion(), original.camelVersion(), original.minimumIgnitionVersion(), 0, List.of(), List.of(), List.of());
        var result = CamelDeploymentManifest.compare(imported, missing);
        assertTrue(result.toString().contains("MISSING_RESOURCE"));
        assertTrue(result.toString().contains("MISSING_LIBRARY"));
        var invalid = new CamelDeploymentManifest.Manifest(99, original.moduleId(), original.moduleVersion(), original.camelVersion(), original.minimumIgnitionVersion(), 0, List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> CamelDeploymentManifest.compare(invalid, original));
    }
}
