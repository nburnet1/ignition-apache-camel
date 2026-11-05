package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CamelExtensionGraphTest {
    private static CamelExtensionGraph.Library library(String hash, String artifact, String scheme) {
        return new CamelExtensionGraph.Library(hash + "-" + artifact + ".jar", artifact + ".jar", artifact, "1", artifact,
            scheme.isEmpty() ? List.of() : List.of(scheme), List.of(), "ACTIVE");
    }
    private static CamelExtensionGraph.Definition definition(String artifact, String hash, String... dependencies) {
        return new CamelExtensionGraph.Definition(artifact, hash, Set.of(dependencies));
    }
    @Test void removesOnlyExclusiveDependenciesAndPreservesSharedLibraries() {
        var http = library("a", "http", "http"); var ftp = library("b", "ftp", "ftp");
        var shared = library("c", "shared", ""); var exclusive = library("d", "http-client", "");
        var graph = new CamelExtensionGraph(List.of(http, ftp, shared, exclusive), Map.of(
            "http", definition("http", "a", "http", "shared", "http-client"), "ftp", definition("ftp", "b", "ftp", "shared")));
        var removal = graph.preview(http.id(), true);
        assertTrue(removal.cleanupSafe());
        assertEquals(Set.of(http.id(), exclusive.id()), Set.copyOf(removal.removeIds()));
        assertEquals(List.of(shared.id()), removal.retainedIds());
        assertEquals(List.of("ftp", "http"), graph.usedBy(shared.id()));
        var dependency = graph.preview(shared.id(), false);
        assertEquals(Set.of("ftp", "http"), Set.copyOf(dependency.dependentGroups()));
        assertEquals(Set.of("ftp", "http"), dependency.schemes());
    }
    @Test void cronRuntimeDependencyMakesQuartzImpactTransitive() {
        var cron = library("a", "cron", "cron"); var quartz = library("b", "quartz", "quartz");
        var graph = new CamelExtensionGraph(List.of(cron, quartz), Map.of(
            "cron", definition("cron", "a", "cron", "quartz"), "quartz", definition("quartz", "b", "quartz")));
        var removal = graph.preview(quartz.id(), false);
        assertEquals(List.of("cron"), removal.dependentGroups());
        assertEquals(Set.of("cron", "quartz"), removal.schemes());
    }
    @Test void unknownUploadsPreventAutomaticDependencyCleanup() {
        var http = library("a", "http", "http"); var dependency = library("b", "client", ""); var unknown = library("c", "custom", "custom");
        var graph = new CamelExtensionGraph(List.of(http, dependency, unknown), Map.of("http", definition("http", "a", "http", "client")));
        var removal = graph.preview(http.id(), true);
        assertFalse(removal.cleanupSafe()); assertEquals(List.of(http.id()), removal.removeIds());
        assertEquals(List.of("custom"), graph.cleanupBlockers());
        assertEquals(List.of(dependency.id()), removal.retainedIds());
    }
    @Test void modifiedRootsAreNotAssignedATrustedDependencyGraph() {
        var http = library("changed", "http", "http");
        var graph = new CamelExtensionGraph(List.of(http), Map.of("http", definition("http", "original", "http", "client")));
        assertFalse(graph.groups().get(0).dependenciesKnown());
        assertFalse(graph.preview(http.id(), true).cleanupSafe());
    }
    @Test void reportsMissingDeclaredLibrariesWithoutPretendingToResolveThem() {
        var root = library("a", "root", "root");
        var graph = new CamelExtensionGraph(List.of(root), Map.of("root", definition("root", "a", "root", "missing")));
        assertEquals(List.of("missing"), graph.groups().get(0).missing());
        assertThrows(IllegalArgumentException.class, () -> graph.preview("not-installed", true));
    }
    @Test void changedSupportingLibraryDisablesCleanupButKeepsTheDeclaredFootprintVisible() {
        var root = library("a", "http", "http"); var changed = library("new", "client", "");
        var graph = new CamelExtensionGraph(List.of(root, changed), Map.of("http",
            new CamelExtensionGraph.Definition("http", "a", Set.of("http", "client"), Map.of("client", "old"))));
        assertFalse(graph.groups().get(0).dependenciesKnown());
        assertTrue(graph.groups().get(0).libraryIds().contains(changed.id()));
        assertEquals(List.of(root.id()), graph.preview(root.id(), true).removeIds());
    }
}
