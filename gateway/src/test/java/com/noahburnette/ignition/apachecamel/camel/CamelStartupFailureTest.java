package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.camel.FailedToCreateRouteException;
import org.apache.camel.NoSuchBeanException;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.ResolveEndpointFailedException;
import org.junit.jupiter.api.Test;

class CamelStartupFailureTest {
    @Test void unwrapsTheActualMissingComponentWithoutRetainingTheEndpointOrMessages() {
        var endpoint = new NoSuchEndpointException("example-mqtt:private-topic?password=private-password");
        var wrapper = new FailedToCreateRouteException("orders", "private-location", "private-source", endpoint);
        var failure = CamelStartupFailure.from(wrapper);
        assertEquals("COMPONENT_UNAVAILABLE", failure.code());
        assertEquals("orders", failure.routeId());
        assertEquals("example-mqtt", failure.component());
        assertEquals(List.of(FailedToCreateRouteException.class.getName(), NoSuchEndpointException.class.getName()), failure.exceptionTypes());
        assertTrue(failure.action().contains("Resources"));
        assertFalse(failure.snapshot().toString().contains("private-"));
        assertFalse(failure.snapshot().toString().contains("example-mqtt:"));
    }

    @Test void classifiesARealCamelRouteCreationFailure() {
        var error = assertThrows(Exception.class, () -> new CamelRouteRuntime("missing-component", new CamelRouteResource("""
            - route:
                id: incoming-orders
                from:
                  uri: example-mqtt:private-topic?password=private-password
                  steps:
                    - setBody:
                        constant: private-business-value
            """)));
        var failure = CamelStartupFailure.from(error);
        assertEquals("COMPONENT_UNAVAILABLE", failure.code());
        assertEquals("example-mqtt", failure.component());
        assertEquals("incoming-orders", failure.routeId());
        assertFalse(failure.snapshot().toString().contains("private-"));
    }

    @Test void providesSubsystemSpecificActionsRatherThanAlwaysSuggestingDependencies() {
        var cases = Map.of(
            new javax.net.ssl.SSLHandshakeException("private-certificate"), "TLS_FAILED",
            new java.net.UnknownHostException("private-host"), "CONNECTION_FAILED",
            new java.net.SocketTimeoutException("private-timeout"), "STARTUP_TIMEOUT",
            new NoClassDefFoundError("private-class"), "LIBRARY_INCOMPATIBLE",
            new NoSuchBeanException("orderValidator"), "REGISTRY_OBJECT_UNAVAILABLE",
            new NoSuchBeanException("ignition.database/ERP"), "DATABASE_UNAVAILABLE",
            new org.apache.camel.NoSuchLanguageException("jsonpath"), "LANGUAGE_UNAVAILABLE",
            new org.apache.camel.ExpressionIllegalSyntaxException("private-expression"), "EXPRESSION_INVALID",
            new org.apache.camel.PropertyBindingException(new Object(), "password", "private-value"), "OPTION_BINDING_FAILED",
            new CamelHttpPathRegistry.DuplicatePathException("/private-path"), "HTTP_PATH_CONFLICT"
        );
        cases.forEach((error, code) -> {
            var failure = CamelStartupFailure.from(error);
            assertEquals(code, failure.code());
            assertFalse(failure.snapshot().toString().contains("private-"));
        });
        var resolve = CamelStartupFailure.from(new ResolveEndpointFailedException("https://private-host?password=private-value", "private-message"));
        assertEquals("ENDPOINT_INVALID", resolve.code());
        assertEquals("https", resolve.component());
        assertFalse(resolve.snapshot().toString().contains("private-"));
    }

    @Test void distinguishesMissingDependenciesWithExplicitHostOwnedMetadata() {
        var failure = CamelStartupFailure.from(new CamelStartupFailure.UnavailableDependency("Component", "demo-mqtt"));
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.code());
        assertEquals("demo-mqtt", failure.reference());
        assertTrue(failure.action().contains("Component"));
    }

    @Test void neverReadsAnArbitraryExceptionMessageOrFormatsTheThrowable() {
        var error = new IllegalArgumentException() {
            @Override public String getMessage() { throw new AssertionError("Do not read arbitrary exception text"); }
            @Override public String toString() { throw new AssertionError("Do not format the exception"); }
        };
        assertEquals("STARTUP", CamelStartupFailure.from(error).code());
    }

    @Test void boundsCauseInspectionAndTerminatesCycles() {
        var first = new Exception("private-first");
        var second = new Exception("private-second", first);
        first.initCause(second);
        assertEquals(2, CamelStartupFailure.from(first).exceptionTypes().size());
        Throwable chain = new NoSuchEndpointException("missing:private-path");
        for (int i = 0; i < 100; i++) chain = new RuntimeException("private-wrapper", chain);
        assertEquals(16, CamelStartupFailure.from(chain).exceptionTypes().size());
    }

    @Test void rejectsUnsafeIdentifiersAndDoesNotTreatExceptionTextAsTrustedMetadata() {
        var wrapper = new FailedToCreateRouteException("https://private-host?token=private-value", "private-location", "private-source",
            new NoSuchEndpointException("private\nvalue:private-path"));
        var failure = CamelStartupFailure.from(wrapper);
        assertEquals("", failure.routeId());
        assertEquals("", failure.component());
        assertFalse(failure.snapshot().toString().contains("private"));
        assertEquals("", CamelStartupFailure.from(new NoSuchBeanException("private\nname")).reference());
        var summary = CamelDiagnostics.summarize("ERROR", List.of(), Map.of(), CamelStartupFailure.from(new NoSuchEndpointException("demo:private-path")));
        assertEquals("ATTENTION", summary.get("status"));
        assertTrue(summary.toString().contains("COMPONENT_UNAVAILABLE"));
        assertFalse(summary.toString().contains("Resource could not start"));
    }
}
