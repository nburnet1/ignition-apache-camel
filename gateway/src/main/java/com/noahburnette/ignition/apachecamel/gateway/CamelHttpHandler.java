package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import com.noahburnette.ignition.apachecamel.camel.IgnitionPlatformHttpEngine;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.python.core.Py;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fixed, authenticated Gateway ingress; consumers cannot mount outside this namespace. */
final class CamelHttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CamelHttpHandler.class);
    private static final com.inductiveautomation.ignition.common.gson.Gson GSON = JsonResourceCodec.buildGson(null);
    private static final int MAX_BODY_BYTES = 65536;
    private final CamelRouteResourceManager manager;

    CamelHttpHandler(CamelRouteResourceManager manager) { this.manager = manager; }

    JsonElement request(RequestContext request, HttpServletResponse response) {
        var caller = Py.getSystemState();
        try {
            var target = manager.publicHttpTarget(request.getPath());
            request.putParameter("resource", target.name());
            // Authenticate against the same immutable runtime snapshot used for delivery.
            // This also supplies native browser CSRF checks on the servlet surface.
            var access = CamelHttpAccess.strategy(ignored -> target.resource()).canAccess(request);
            if (access != RouteAccess.GRANTED) {
                return error(response, access == RouteAccess.UNAUTHORIZED ? 401 : 403, "HTTP invocation denied");
            }
            var servlet = request.getRequest();
            byte[] bytes = servlet.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) return error(response, 413, "JSON body exceeds 64 KiB");
            Object body = null;
            if (bytes.length > 0) {
                String type = servlet.getContentType();
                if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                    return error(response, 415, "Use application/json");
                }
                try {
                    body = GatewayCamelServices.toJava(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)));
                } catch (RuntimeException invalid) {
                    return error(response, 400, "Invalid JSON body");
                }
            }
            Map<String, Object> headers = new LinkedHashMap<>();
            // Never forward client-supplied Camel headers, cookies, credentials, or CSRF tokens.
            headers.put(Exchange.HTTP_METHOD, "POST");
            headers.put(Exchange.HTTP_PATH, target.path());
            headers.put(Exchange.HTTP_URI, servlet.getRequestURI());
            headers.put(Exchange.CONTENT_TYPE, "application/json");
            headers.put("ignitionHttpActor", request.getActor());
            var reply = target.http().request(target.path(), body, headers);
            JsonElement result = GSON.toJsonTree(reply.body());
            if (GSON.toJson(result).getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                return error(response, 500, "HTTP route response exceeds 64 KiB");
            }
            response.setStatus(reply.status());
            return result;
        } catch (IgnitionPlatformHttpEngine.HttpFailure expected) {
            return error(response, expected.status(), expected.getMessage());
        } catch (Exception failure) {
            LOGGER.error("Camel HTTP request failed for resource {}", request.getParameter("resource"), failure);
            return error(response, 500, "Camel HTTP route failed; see Gateway logs");
        } finally {
            Py.setSystemState(caller);
        }
    }

    private static JsonElement error(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        return GSON.toJsonTree(Map.of("error", message));
    }
}
