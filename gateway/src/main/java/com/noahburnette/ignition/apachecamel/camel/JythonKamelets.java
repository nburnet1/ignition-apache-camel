package com.noahburnette.ignition.apachecamel.camel;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RouteTemplateDefinition;

/** Project-library authoring of exactly one native template; no private interpreter. */
public final class JythonKamelets {
    private JythonKamelets() { }
    @FunctionalInterface public interface Configurer { void configure(Builder builder) throws Exception; }
    public static void load(DefaultCamelContext context, String name, Configurer function) throws Exception {
        if (context.getRouteTemplateDefinition(name) != null) throw new IllegalArgumentException("Duplicate template ID: " + name);
        int templates = context.getRouteTemplateDefinitions().size();
        int routes = context.getRouteDefinitions().size();
        JythonRoutes.preservingCallerState(() -> {
            context.addRoutes(new Builder(name) {
                @Override public void configure() throws Exception { function.configure(this); }
            });
            return null;
        });
        if (context.getRouteTemplateDefinitions().size() != templates + 1 || context.getRouteTemplateDefinition(name) == null
                || context.getRouteDefinitions().size() != routes)
            throw new IllegalArgumentException("A Kamelet function must build exactly one named template, not running routes");
    }
    public abstract static class Builder extends RouteBuilder {
        private final String name;
        private RouteTemplateDefinition template;
        protected Builder(String name) { this.name = name; }
        public RouteTemplateDefinition template() {
            if (template == null) template = routeTemplate(name);
            return template;
        }
        public RouteDefinition from_(String uri) { return template().from(uri); }
    }
}
