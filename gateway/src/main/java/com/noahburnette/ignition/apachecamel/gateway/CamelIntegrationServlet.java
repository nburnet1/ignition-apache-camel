package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.noahburnette.ignition.apachecamel.camel.CamelHttpPublicPaths;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Stable public ingress on the Gateway-owned server, not a proxy or another listener. */
public final class CamelIntegrationServlet extends HttpServlet {
    private static final String OWNER = CamelIntegrationServlet.class.getName() + ".owner";

    static void register(GatewayContext context, CamelRouteResourceManager manager) {
        var web = context.getWebResourceManager();
        var servletContext = web.getServletContext();
        synchronized (servletContext) {
            if (servletContext.getAttribute(OWNER) != null || servletContext.getServletRegistration("integrations") != null) {
                throw new IllegalStateException("The integrations servlet namespace is already registered");
            }
            servletContext.setAttribute(OWNER, manager);
            try { web.addServlet("integrations", CamelIntegrationServlet.class); }
            catch (RuntimeException failure) { servletContext.removeAttribute(OWNER); throw failure; }
        }
    }

    static void unregister(GatewayContext context, CamelRouteResourceManager manager) {
        var web = context.getWebResourceManager();
        var servletContext = web.getServletContext();
        synchronized (servletContext) {
            if (servletContext.getAttribute(OWNER) == manager) {
                servletContext.removeAttribute(OWNER);
                web.removeServlet("integrations");
            }
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            writeError(response, 405, "Only POST is supported");
            return;
        }
        String prefix = request.getContextPath() + CamelHttpPublicPaths.PREFIX;
        String uri = request.getRequestURI();
        String path = uri.startsWith(prefix + "/") ? uri.substring(prefix.length()) : null;
        if (!CamelHttpPublicPaths.validPublicPath(path)) {
            writeError(response, 404, "Unknown HTTP integration path");
            return;
        }
        var owner = request.getServletContext().getAttribute(OWNER);
        if (!(owner instanceof CamelRouteResourceManager manager)) {
            writeError(response, 503, "HTTP integration is unavailable");
            return;
        }
        var context = new RequestContext(request, path);
        var result = new CamelHttpHandler(manager).request(context, response);
        response.getWriter().write(result.toString());
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        var result = new com.inductiveautomation.ignition.common.gson.JsonObject();
        result.addProperty("error", message);
        response.getWriter().write(result.toString());
    }
}
