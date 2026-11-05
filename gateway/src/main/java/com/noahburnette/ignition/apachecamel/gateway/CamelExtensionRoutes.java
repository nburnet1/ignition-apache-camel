package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.noahburnette.ignition.apachecamel.camel.CamelComponentCatalog;
import com.noahburnette.ignition.apachecamel.camel.CamelExtensions;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Raw binary requests avoid multipart temp files and enforce a streaming upload bound. */
final class CamelExtensionRoutes {
    static void mount(RouteGroup routes, CamelExtensions extensions, CamelRouteResourceManager manager) {
        routes.newRoute("/camel-extensions").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.READ).nocache().handler((request, response) -> {
                synchronized (extensions) {
                    var graph = extensions.graph(); var files = extensions.inventory();
                    return new Gson().toJsonTree(Map.of("version", CamelComponentCatalog.VERSION, "maxJarBytes", CamelExtensions.MAX_JAR_BYTES,
                        "startupError", extensions.startupError() == null ? "" : extensions.startupError(), "files", files, "groups", graph.groups(),
                        "usage", files.stream().collect(java.util.stream.Collectors.toMap(file -> file.get("id").toString(), file -> graph.usedBy(file.get("id").toString())))));
                }
            }).mount();
        routes.newRoute("/camel-extension-removal-preview").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.READ).nocache().handler((request, response) -> {
                try { return new Gson().toJsonTree(CamelExtensionPlans.preview(extensions, manager, request.getParameter("id"), "true".equals(request.getParameter("includeUnused")))); }
                catch (IllegalArgumentException missing) { response.setStatus(404); return new Gson().toJsonTree(Map.of("error", missing.getMessage())); }
                catch (IllegalStateException changed) { response.setStatus(409); return new Gson().toJsonTree(Map.of("error", changed.getMessage())); }
            }).mount();
        routes.newRoute("/camel-component-metadata").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.READ).nocache().handler((request, response) -> new Gson().toJsonTree(Map.of("components", extensions.schemas()))).mount();
        routes.newRoute("/camel-extension-upload").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.WRITE).nocache().handler((request, response) -> {
                try {
                    if (!"true".equals(request.getParameter("confirm"))) throw new IllegalArgumentException("Confirm that the uploaded libraries are trusted executable code");
                    var servlet = request.getRequest();
                    if (!"application/java-archive".equals(servlet.getContentType())) throw new IllegalArgumentException("Use application/java-archive");
                    if (servlet.getContentLengthLong() > CamelExtensions.MAX_JAR_BYTES) {
                        response.setStatus(413); return new Gson().toJsonTree(Map.of("error", "JAR exceeds 64 MiB"));
                    }
                    String id = extensions.upload(request.getParameter("name"), servlet.getInputStream());
                    LoggerFactory.getLogger(CamelExtensionRoutes.class).info("Camel extension staged by {}: {}", request.getActor(), id);
                    return new Gson().toJsonTree(Map.of("id", id, "restartRequired", true));
                } catch (IllegalArgumentException invalid) {
                    response.setStatus(400); return new Gson().toJsonTree(Map.of("error", invalid.getMessage()));
                } catch (Exception failure) {
                    LoggerFactory.getLogger(CamelExtensionRoutes.class).warn("Camel extension upload rejected", failure);
                    response.setStatus(400); return new Gson().toJsonTree(Map.of("error", "Could not stage this JAR. Check that it is valid and see Gateway logs."));
                }
            }).mount();
        routes.newRoute("/camel-extension-remove").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.WRITE).nocache().handler((request, response) -> {
                try {
                    if (!"true".equals(request.getParameter("confirm"))) throw new IllegalArgumentException("Explicit removal confirmation is required");
                    String token = request.getParameter("token");
                    boolean cleanup = "true".equals(request.getParameter("includeUnused"));
                    if (token != null || cleanup) CamelExtensionPlans.remove(extensions, manager, request.getParameter("id"), cleanup, token);
                    else extensions.remove(request.getParameter("id")); // Retain the explicit single-JAR maintenance API.
                    LoggerFactory.getLogger(CamelExtensionRoutes.class).info("Camel extension removal staged by {}: {}", request.getActor(), request.getParameter("id"));
                    return new Gson().toJsonTree(Map.of("restartRequired", true));
                } catch (IllegalArgumentException invalid) {
                    response.setStatus(400); return new Gson().toJsonTree(Map.of("error", invalid.getMessage()));
                } catch (IllegalStateException changed) {
                    response.setStatus(409); return new Gson().toJsonTree(Map.of("error", changed.getMessage()));
                }
            }).mount();
    }
}
