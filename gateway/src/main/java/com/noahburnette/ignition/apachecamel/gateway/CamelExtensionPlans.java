package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.noahburnette.ignition.apachecamel.camel.CamelExtensions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Revalidate both the library set and resource configuration before publishing a removal plan. */
final class CamelExtensionPlans {
    static Map<String, Object> preview(CamelExtensions extensions, CamelRouteResourceManager manager, String id, boolean includeUnused) throws Exception {
        List<Map<String, Object>> inventory;
        com.noahburnette.ignition.apachecamel.camel.CamelExtensionGraph graph;
        synchronized (extensions) {
            inventory = extensions.inventory();
            graph = extensions.graph();
        }
        var removal = graph.preview(id, includeUnused && extensions.startupError() == null);
        String before = manager.extensionRevision();
        var impact = manager.extensionImpact(removal);
        String after = manager.extensionRevision();
        if (!before.equals(after)) throw new IllegalStateException("Configuration changed; refresh the impact preview");
        var group = graph.groups().stream().filter(value -> value.id().equals(id)).findFirst();
        String version = libraryRevision(inventory);
        if (!version.equals(libraryRevision(extensions.inventory()))) throw new IllegalStateException("Libraries changed; refresh the impact preview");
        String resourceVersion = digest(after);
        String token = digest(id + ":" + includeUnused + ":" + version + ":" + resourceVersion
            + ":" + new Gson().toJson(graph.groups()) + ":" + String.join(",", removal.removeIds()));
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("token", token); result.put("includeUnused", includeUnused);
        result.put("title", group.map(value -> value.title()).orElseGet(() -> inventory.stream().filter(file -> file.get("id").equals(id)).findFirst().orElseThrow().get("name").toString()));
        result.put("dependenciesKnown", group.map(value -> value.dependenciesKnown()).orElse(false));
        result.put("cleanupSafe", removal.cleanupSafe() && extensions.startupError() == null);
        result.put("cleanupBlockers", graph.cleanupBlockers());
        result.put("remove", inventory.stream().filter(file -> removal.removeIds().contains(file.get("id"))).toList());
        result.put("retain", inventory.stream().filter(file -> removal.retainedIds().contains(file.get("id"))).toList());
        result.put("dependentGroups", removal.dependentGroups());
        result.put("components", impact.components()); result.put("routes", impact.routes()); result.put("possibleRoutes", impact.possibleRoutes());
        result.put("restartRequired", true);
        result.put("libraryRevision", version); result.put("resourceRevision", resourceVersion);
        return result;
    }

    static void remove(CamelExtensions extensions, CamelRouteResourceManager manager, String id, boolean includeUnused, String token) throws Exception {
        var current = preview(extensions, manager, id, includeUnused);
        if (token == null || !token.equals(current.get("token"))) throw new IllegalStateException("Libraries or resource configuration changed; review a fresh impact preview");
        if (!digest(manager.extensionRevision()).equals(current.get("resourceRevision"))) throw new IllegalStateException("Configuration changed; review a fresh impact preview");
        synchronized (extensions) {
            if (!libraryRevision(extensions.inventory()).equals(current.get("libraryRevision"))) throw new IllegalStateException("Libraries changed; review a fresh impact preview");
            @SuppressWarnings("unchecked") var remove = (List<Map<String, Object>>) current.get("remove");
            extensions.removeAll(remove.stream().map(file -> file.get("id").toString()).toList());
        }
    }
    private static String libraryRevision(List<Map<String, Object>> files) throws Exception {
        return digest(files.stream().map(file -> file.get("id") + ":" + file.get("state") + ":" + file.get("bytes")).sorted().collect(java.util.stream.Collectors.joining("\n")));
    }
    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
