package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Static configuration relationships only. No endpoint construction or project-code execution. */
public final class CamelDependencyGraph {
    public record Ref(String kind, String name, String confidence, String reason) {
        public Ref(String kind, String name, String reason) { this(kind, name, "DECLARED", reason); }
        public String key() { return kind + ":" + name; }
    }
    public record Node(String kind, String name, String state, boolean enabled, boolean external, String fingerprint,
                       List<Ref> dependencies, List<String> uncertainties, List<Map<String, Object>> parameters) {
        public Node { dependencies = List.copyOf(dependencies); uncertainties = List.copyOf(uncertainties); parameters = List.copyOf(parameters); }
        public String key() { return kind + ":" + name; }
    }
    private final Map<String, Node> nodes;
    public CamelDependencyGraph(List<Node> definitions) {
        var indexed = new LinkedHashMap<String, Node>();
        for (var node : definitions) {
            if (indexed.putIfAbsent(node.key(), node) != null) throw new IllegalArgumentException("Duplicate dependency node");
        }
        nodes = Map.copyOf(indexed);
    }
    public List<Node> nodes() { return nodes.values().stream().sorted(Comparator.comparing(Node::key)).toList(); }
    public Map<String, Object> view(String kind, String name) {
        var node = nodes.get(kind + ":" + name);
        if (node == null) throw new IllegalArgumentException("Unknown Camel configuration");
        var requires = walk(node, false);
        var usedBy = walk(node, true);
        var notes = new LinkedHashSet<>(node.uncertainties());
        for (var row : requires) {
            var dependency = nodes.get(row.get("kind") + ":" + row.get("name"));
            if (dependency != null) notes.addAll(dependency.uncertainties());
        }
        if (nodes.values().stream().anyMatch(value -> !value.uncertainties().isEmpty()))
            notes.add("Script and dynamic references elsewhere may add uses that cannot be proven statically. This is not a complete execution graph.");
        return Map.of("resource", node, "requires", requires, "usedBy", usedBy, "uncertainties", List.copyOf(notes),
            "impact", "Edits may rebuild dependent route contexts; disabling/deleting a required definition can stop them. No changes are performed by this view.");
    }
    private record Visit(Ref ref, int depth) { }
    private List<Map<String, Object>> walk(Node root, boolean reverse) {
        var result = new ArrayList<Map<String, Object>>();
        var pending = new ArrayDeque<Visit>();
        var seen = new LinkedHashSet<String>(); seen.add(root.key());
        if (reverse) addUsers(root.key(), 1, "DECLARED", pending);
        else root.dependencies().forEach(ref -> pending.add(new Visit(ref, 1)));
        while (!pending.isEmpty()) {
            var visit = pending.removeFirst();
            var ref = visit.ref();
            if (!seen.add(ref.key())) continue;
            var node = nodes.get(ref.key());
            result.add(Map.of("kind", ref.kind(), "name", ref.name(), "state", node == null ? "MISSING" : node.state(),
                "relationship", visit.depth() == 1 ? "Direct" : "Transitive", "depth", visit.depth(),
                "confidence", ref.confidence(), "reason", ref.reason()));
            if (reverse) addUsers(ref.key(), visit.depth() + 1, ref.confidence(), pending);
            else if (node != null) for (var dependency : node.dependencies()) {
                String confidence = "POSSIBLE".equals(ref.confidence()) ? "POSSIBLE" : dependency.confidence();
                pending.add(new Visit(new Ref(dependency.kind(), dependency.name(), confidence, dependency.reason()), visit.depth() + 1));
            }
        }
        return List.copyOf(result);
    }
    private void addUsers(String key, int depth, String confidence, ArrayDeque<Visit> pending) {
        for (var node : nodes()) for (var dependency : node.dependencies()) if (dependency.key().equals(key))
            pending.add(new Visit(new Ref(node.kind(), node.name(), "POSSIBLE".equals(confidence) ? "POSSIBLE" : dependency.confidence(), dependency.reason()), depth));
    }
}
