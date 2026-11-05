package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable removal planning. Unknown third-party dependencies are never guessed or garbage-collected. */
public final class CamelExtensionGraph {
    public record Library(String id, String name, String artifact, String version, String title,
        List<String> schemes, List<String> features, String state) { }
    public record Definition(String artifact, String sha256, Set<String> artifacts, Map<String, String> libraryHashes) {
        public Definition(String artifact, String sha256, Set<String> artifacts) { this(artifact, sha256, artifacts, Map.of()); }
    }
    public record Group(String id, String title, String version, String state, boolean dependenciesKnown,
        List<String> libraryIds, List<String> missing, List<String> schemes, List<String> features) { }
    public record Removal(List<String> removeIds, List<String> retainedIds, List<String> dependentGroups,
        Set<String> schemes, Set<String> features, boolean cleanupSafe) { }

    private final List<Library> libraries;
    private final List<Group> groups;

    public CamelExtensionGraph(List<Library> libraries, Map<String, Definition> definitions) {
        this.libraries = List.copyOf(libraries);
        var result = new ArrayList<Group>();
        for (var root : libraries) {
            var definition = definitions.get(root.artifact());
            if (definition == null && root.schemes().isEmpty() && root.features().isEmpty()) continue;
            boolean known = definition != null && root.id().startsWith(definition.sha256() + "-");
            boolean verified = known;
            var members = new LinkedHashSet<String>(); members.add(root.id());
            var missing = new ArrayList<String>();
            if (known) for (String artifact : definition.artifacts().stream().sorted().toList()) {
                if (artifact.equals(root.artifact())) continue;
                var candidates = libraries.stream().filter(file -> artifact.equals(file.artifact()) && !pending(file.state())).toList();
                if (candidates.isEmpty()) missing.add(artifact);
                candidates.forEach(file -> members.add(file.id()));
                String hash = definition.libraryHashes().get(artifact);
                if (hash != null && candidates.stream().anyMatch(file -> !file.id().startsWith(hash + "-"))) verified = false;
            }
            result.add(new Group(root.id(), root.title(), root.version(), root.state(), verified && missing.isEmpty(),
                members.stream().sorted().toList(), List.copyOf(missing), root.schemes(), root.features()));
        }
        groups = result.stream().sorted(java.util.Comparator.comparing(Group::title).thenComparing(Group::id)).toList();
    }

    public List<Group> groups() { return groups; }
    public List<String> usedBy(String id) {
        return groups.stream().filter(group -> !pending(group.state()) && group.libraryIds().contains(id)).map(Group::title).distinct().sorted().toList();
    }
    public List<String> cleanupBlockers() {
        var accounted = new HashSet<String>(); groups.forEach(group -> accounted.addAll(group.libraryIds()));
        var blockers = new java.util.TreeSet<String>();
        groups.stream().filter(group -> !group.dependenciesKnown() && !pending(group.state())).forEach(group -> blockers.add(group.title()));
        libraries.stream().filter(file -> !pending(file.state()) && !accounted.contains(file.id())).forEach(file -> blockers.add(file.name()));
        return List.copyOf(blockers);
    }
    public Removal preview(String id, boolean includeUnused) {
        if (libraries.stream().noneMatch(file -> file.id().equals(id))) throw new IllegalArgumentException("Unknown extension library");
        Group target = groups.stream().filter(group -> group.id().equals(id)).findFirst().orElse(null);
        var remove = new HashSet<String>(); remove.add(id);
        var retained = new HashSet<String>();
        boolean safe = target != null && target.dependenciesKnown() && cleanupBlockers().isEmpty();
        if (target != null) for (String member : target.libraryIds()) {
            if (member.equals(id)) continue;
            boolean shared = groups.stream().anyMatch(group -> !group.id().equals(id) && !pending(group.state()) && group.libraryIds().contains(member));
            if (includeUnused && safe && !shared) remove.add(member); else retained.add(member);
        }
        var affectedSchemes = new HashSet<String>();
        var affectedFeatures = new HashSet<String>();
        var dependents = new ArrayList<String>();
        for (var group : groups) if (group.libraryIds().stream().anyMatch(remove::contains)) {
            affectedSchemes.addAll(group.schemes()); affectedFeatures.addAll(group.features());
            if (!group.id().equals(id) && !pending(group.state())) dependents.add(group.title());
        }
        // Opaque libraries may be referenced by any uploaded code; the impact layer marks that uncertainty.
        return new Removal(remove.stream().sorted().toList(), retained.stream().sorted().toList(),
            dependents.stream().distinct().sorted().toList(), Set.copyOf(affectedSchemes), Set.copyOf(affectedFeatures), safe);
    }
    private static boolean pending(String state) { return "REMOVAL_PENDING".equals(state); }
}
