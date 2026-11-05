package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.noahburnette.ignition.apachecamel.camel.CamelHawtio;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.python.core.Py;
import org.slf4j.LoggerFactory;

/** Same-origin/session transport. External consoles must use an authenticated server-side proxy. */
final class CamelHawtioRoutes {
    private final CamelHawtio hawtio;
    CamelHawtioRoutes(CamelHawtio hawtio) { this.hawtio = hawtio; }

    void mount(RouteGroup routes) {
        for (String path : new String[] { "/camel-jolokia", "/camel-jolokia/" }) {
            // Jolokia POST can contain management actions: always use native WRITE + POST CSRF protection.
            routes.newRoute(path).method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE).nocache().handler(this::post).mount();
            routes.newRoute(path).method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache().handler(this::version).mount();
        }
        // Some Hawtio versions probe version with GET. No other GET commands, especially exec, are mounted.
        routes.newRoute("/camel-jolokia/version").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.READ).nocache().handler(this::version).mount();
    }

    private JsonElement version(RequestContext request, HttpServletResponse response) {
        try { return JsonParser.parseString(hawtio.post("{\"type\":\"version\"}".getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { return error(response, 503, "Camel management unavailable"); }
    }

    private JsonElement post(RequestContext request, HttpServletResponse response) {
        var caller = Py.getSystemState();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(CamelHawtio.class.getClassLoader());
            var servlet = request.getRequest();
            String origin = servlet.getHeader("Origin");
            if (origin != null) {
                URI uri = URI.create(origin);
                int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!servlet.getScheme().equals(uri.getScheme()) || !servlet.getServerName().equalsIgnoreCase(uri.getHost())
                    || servlet.getServerPort() != port) return error(response, 403, "Cross-origin management is disabled");
            }
            String type = servlet.getContentType();
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                return error(response, 415, "Use application/json");
            }
            byte[] bytes = servlet.getInputStream().readNBytes(CamelHawtio.MAX_REQUEST_BYTES + 1);
            if (bytes.length > CamelHawtio.MAX_REQUEST_BYTES) return error(response, 413, "Management body exceeds 64 KiB");
            return JsonParser.parseString(hawtio.post(bytes));
        } catch (CamelHawtio.BusyException busy) {
            return error(response, 429, "Camel management is busy");
        } catch (SecurityException denied) {
            return error(response, 403, "Management request is disabled");
        } catch (IllegalArgumentException | org.jolokia.json.parser.ParseException invalid) {
            return error(response, 400, "Invalid management request");
        } catch (Exception failure) {
            LoggerFactory.getLogger(CamelHawtioRoutes.class).warn("Camel management request failed", failure);
            return error(response, 500, "Camel management request failed; see Gateway logs");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            Py.setSystemState(caller);
        }
    }

    private static JsonElement error(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        var object = new com.inductiveautomation.ignition.common.gson.JsonObject();
        object.addProperty("error", message);
        return object;
    }
}
