package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.ExpressionIllegalSyntaxException;
import org.apache.camel.FailedToCreateRouteException;
import org.apache.camel.FailedToStartRouteException;
import org.apache.camel.NoSuchBeanException;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.NoSuchLanguageException;
import org.apache.camel.PropertyBindingException;
import org.apache.camel.ResolveEndpointFailedException;

/** Bounded startup metadata. Never retains Throwable, exception text, source or full endpoint URIs. */
public record CamelStartupFailure(String code, String title, String action, String routeId,
                                  String component, String reference, List<String> exceptionTypes) {
    private static final int MAX_CAUSES = 16;

    public static CamelStartupFailure from(Throwable failure) {
        String code = "STARTUP", title = "Resource could not start";
        String action = "Review Definition and Resources. The cause types below identify the failing subsystem; inspect Gateway logs for details not safe to display here.";
        String routeId = "", component = "", reference = "";
        var types = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && types.size() < MAX_CAUSES && seen.add(cause); cause = cause.getCause()) {
            types.add(cause.getClass().getName());
            if (routeId.isEmpty() && cause instanceof FailedToCreateRouteException route) routeId = identifier(route.getRouteId());
            if (routeId.isEmpty() && cause instanceof FailedToStartRouteException route) routeId = identifier(route.getRouteId());
            if (component.isEmpty() && cause instanceof ResolveEndpointFailedException endpoint) component = scheme(endpoint.getUri());
            if (cause instanceof NoSuchEndpointException endpoint) {
                component = scheme(endpoint.getUri());
                code = "COMPONENT_UNAVAILABLE"; title = "Endpoint component is unavailable";
                action = "For a configured component, attach its connection or scheduling profile under Resources and check that it is enabled in Library. For a built-in Camel scheme, check Extensions for its active library. The URI scheme must match the profile name; staged libraries require a Gateway restart.";
            } else if (cause instanceof NoSuchBeanException bean) {
                boolean nativeDatabase = bean.getName() != null && bean.getName().startsWith("ignition.database/");
                code = nativeDatabase ? "DATABASE_UNAVAILABLE" : "REGISTRY_OBJECT_UNAVAILABLE";
                title = nativeDatabase ? "Ignition database connection was not found" : "Registry object was not found";
                reference = identifier(nativeDatabase ? bean.getName().substring("ignition.database/".length()) : bean.getName());
                action = nativeDatabase
                    ? "Check the native connection in Connections > Databases > Connections and use its exact name after ignition.database/. No Camel database binding is required."
                    : "Attach the required connection, certificate or advanced object under Resources, check that it is enabled in Library, and match the reference name in Definition. Also check supporting objects included by attached connections.";
            } else if (cause instanceof NoSuchLanguageException language) {
                code = "LANGUAGE_UNAVAILABLE"; title = "Expression language is unavailable"; reference = identifier(language.getLanguage());
                action = "Check the expression language name in Definition and its library in Extensions. Newly staged libraries become available after a Gateway restart.";
            } else if (cause instanceof PropertyBindingException) {
                code = "OPTION_BINDING_FAILED"; title = "A configuration option could not be applied";
                action = "Check option names, value types and Registry references in Definition and the selected Component. Use the options supported by the installed Camel version.";
            } else if (cause instanceof ExpressionIllegalSyntaxException) {
                code = "EXPRESSION_INVALID"; title = "An expression could not be parsed";
                action = "Check expression syntax and quoting in Definition. Expressions are omitted here because they can contain sensitive data.";
            } else if (cause instanceof CamelHttpPathRegistry.DuplicatePathException) {
                code = "HTTP_PATH_CONFLICT"; title = "HTTP path already registered";
                action = "Choose a unique exact HTTP consumer path. Review registered HTTP ingress paths in other route resources; each public path can have only one owner.";
            } else if (cause instanceof UnavailableDependency dependency) {
                code = "DEPENDENCY_UNAVAILABLE"; title = "A selected dependency is unavailable"; reference = identifier(dependency.name);
                action = "Check the selected " + dependency.kind + " under Resources and its definition in Library. It must exist, be enabled and have a valid configuration.";
            } else if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) {
                code = "TLS_FAILED"; title = "TLS setup or certificate verification failed";
                action = "Check the selected certificate configuration in Library, trust chain, certificate validity, hostname and client-certificate requirements. Do not disable certificate verification.";
            } else if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
                || cause instanceof java.net.NoRouteToHostException) {
                code = "CONNECTION_FAILED"; title = "A required service could not be reached";
                action = "Check the configured host, port, DNS, network access and service availability from the Gateway. Verify the selected Component connection settings.";
            } else if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
                code = "STARTUP_TIMEOUT"; title = "Startup timed out";
                action = "Check downstream availability and the selected Component timeouts. A timeout does not prove that a remote side effect did not happen.";
            } else if (cause instanceof LinkageError || cause instanceof ClassNotFoundException) {
                code = "LIBRARY_INCOMPATIBLE"; title = "A required class could not be loaded";
                action = "Check Extensions for missing, blocked or incompatible libraries and their dependencies. Use Camel 4.22.2 / Java 17 compatible JARs; staged changes require a Gateway restart.";
            } else if ("STARTUP".equals(code) && cause instanceof ResolveEndpointFailedException) {
                code = "ENDPOINT_INVALID"; title = "An endpoint could not be configured";
                action = "Check the URI syntax, supported endpoint options, property placeholders and Registry references in Definition. Full endpoint URIs are omitted here.";
            }
        }
        return new CamelStartupFailure(code, title, action, routeId, component, reference, List.copyOf(types));
    }

    public Map<String, Object> snapshot() {
        return Map.of("code", code, "title", title, "action", action, "routeId", routeId,
            "component", component, "reference", reference, "exceptionTypes", exceptionTypes);
    }

    private static String identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}") ? value : "";
    }

    private static String scheme(String uri) {
        if (uri == null) return "";
        int colon = uri.indexOf(':');
        if (colon < 1 || colon > 64) return "";
        String scheme = uri.substring(0, colon);
        return scheme.matches("[a-z][a-z0-9-]{0,63}") ? scheme : "";
    }

    /** Explicit metadata for host-owned dependency checks; never infer safety from an exception message. */
    public static final class UnavailableDependency extends IllegalStateException {
        private final String kind;
        private final String name;
        public UnavailableDependency(String kind, String name) {
            super("Selected Camel dependency is missing, disabled or invalid");
            this.kind = switch (kind) { case "Component", "Registry entry", "Kamelet" -> kind; default -> "resource"; };
            this.name = name;
        }
    }
}
