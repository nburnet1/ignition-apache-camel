package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.noahburnette.ignition.apachecamel.camel.CamelDependencyGraph;
import com.noahburnette.ignition.apachecamel.camel.CamelExtensions;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read-only operations views. Native session POST comparison requires WRITE/CSRF but performs no writes. */
final class CamelOperationsRoutes {
    private final GatewayContext gateway;
    private final CamelRouteResourceManager manager;
    private final CamelExtensions extensions;
    CamelOperationsRoutes(GatewayContext gateway, CamelRouteResourceManager manager, CamelExtensions extensions) {
        this.gateway = gateway; this.manager = manager; this.extensions = extensions;
    }
    private record Snapshot(CamelDependencyGraph graph, CamelDeploymentManifest.Manifest manifest, Map<String, String> projects) { }
    private Snapshot snapshot() throws Exception {
        String before = manager.extensionRevision();
        var resources = manager.configurationSnapshots();
        var projects = new java.util.HashMap<String, String>();
        gateway.getProjectManager().getManifests().forEach((name, manifest) -> projects.put(name,
            !manifest.enabled() ? "DISABLED" : manifest.inheritable() ? "INHERITABLE_ONLY" : "AVAILABLE"));
        var graph = CamelOperationsModel.graph(resources, projects);
        // Never hold the extension monitor while taking the runtime-registry monitor.
        var inventory = extensions.inventory();
        var module = gateway.getModuleManager().getModule(IgnitionApacheCamelGatewayHook.MODULE_ID);
        var manifest = CamelDeploymentManifest.versioned(CamelDeploymentManifest.create(graph, inventory),
            module == null ? "unknown" : module.getInfo().getVersion().toString());
        if (!before.equals(manager.extensionRevision()) || !inventory.equals(extensions.inventory()))
            throw new IllegalStateException("Configuration or libraries changed; refresh the view");
        return new Snapshot(graph, manifest, Map.copyOf(projects));
    }
    void mount(RouteGroup routes) {
        routes.newRoute("/camel-dependencies").handler((request, response) -> {
            try { return new Gson().toJsonTree(snapshot().graph().view(request.getParameter("kind"), request.getParameter("name"))); }
            catch (IllegalArgumentException missing) { response.setStatus(404); return error("Unknown Camel configuration"); }
            catch (Exception changed) { response.setStatus(409); return error("Configuration changed or inventory unavailable; refresh the view"); }
        }).method(HttpMethod.GET).type(RouteGroup.TYPE_JSON).requirePermission(PermissionType.READ).nocache().mount();
        routes.newRoute("/camel-deployment-manifest").handler((request, response) -> {
            try { return new Gson().toJsonTree(snapshot().manifest()); }
            catch (Exception changed) { response.setStatus(409); return error("Configuration changed or inventory unavailable; refresh before exporting"); }
        }).method(HttpMethod.GET).type(RouteGroup.TYPE_JSON).requirePermission(PermissionType.READ).nocache().mount();
        routes.newRoute("/camel-deployment-compare").handler((request, response) -> {
            try {
                if (request.getRequest().getContentLengthLong() > CamelDeploymentManifest.MAX_BYTES) {
                    response.setStatus(413); return error("Manifest exceeds 4 MiB; no changes were made");
                }
                byte[] input = request.getRequest().getInputStream().readNBytes(CamelDeploymentManifest.MAX_BYTES + 1);
                if (input.length > CamelDeploymentManifest.MAX_BYTES) throw new IllegalArgumentException("Manifest exceeds 4 MiB");
                var json = JsonParser.parseString(new String(input, StandardCharsets.UTF_8));
                var expected = new Gson().fromJson(json, CamelDeploymentManifest.Manifest.class);
                var target = snapshot();
                return new Gson().toJsonTree(CamelDeploymentManifest.compare(expected, target.manifest(), target.projects()));
            } catch (IllegalStateException changed) { response.setStatus(409); return error("Configuration changed; retry comparison"); }
            catch (Exception invalid) { response.setStatus(400); return error("Choose a valid version-1 deployment manifest up to 4 MiB; no changes were made"); }
        }).method(HttpMethod.POST).type(RouteGroup.TYPE_JSON).requirePermission(PermissionType.WRITE).nocache().mount();
    }
    private static com.inductiveautomation.ignition.common.gson.JsonElement error(String text) { return new Gson().toJsonTree(Map.of("error", text)); }
}
