package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

class JythonRoutesTest {
    @Test
    void invokesTheReferencedFunctionWithoutOwningItsInterpreter() throws Exception {
        var caller = Py.getSystemState();
        try (var project = new PythonInterpreter(null, new PySystemState())) {
            project.getSystemState().setClassLoader(JythonRoutes.class.getClassLoader());
            project.exec("""
                from org.apache.camel import Processor
                prefix = "Project says "
                class Greeting(Processor):
                    def process(self, exchange):
                        exchange.getMessage().setBody(prefix + str(exchange.getMessage().getBody()))
                def orders(builder):
                    builder.from_("direct:greet").routeId("greet").process(Greeting())
                """);
            var before = Py.getSystemState();
            try (var runtime = new CamelRouteRuntime("project", reference(), context -> { },
                builder -> project.get("orders").__call__(Py.java2py(builder)))) {
                assertEquals("Project says Camel", runtime.request("direct:greet", "Camel"));
                assertEquals("Project says again", runtime.request("direct:greet", "again"));
            }
            assertSame(before, Py.getSystemState());
            assertEquals("Project says ", project.get("prefix").toString(), "the borrowed project namespace must stay alive");
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void validatesReferencesWithoutLookingUpOrExecutingProjectCode() throws Exception {
        CamelRouteRuntime.validate(reference()); // No Gateway or script resolver is needed for validation.
        assertThrows(IllegalArgumentException.class, () -> CamelRouteRuntime.validate(
            new CamelRouteResource("JYTHON", null, "", "routes.orders()")));
        var errors = new CamelRouteResource("JYTHON", null, "", "routes.orders()").validationErrors();
        assertTrue(errors.containsKey("project"));
        assertTrue(errors.containsKey("functionPath"));
    }

    @Test
    void rejectsAnUnavailableResolverOrEmptyRouteModelAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new CamelRouteRuntime("missing", reference()));
        assertThrows(IllegalArgumentException.class, () -> new CamelRouteRuntime(
            "empty", reference(), context -> { }, builder -> { }));
    }

    @Test
    void projectReferencesNeverFallBackToAnInactiveYamlDraft() throws Exception {
        var resource = new CamelRouteResource("JYTHON", "invalid YAML draft", "MyProject", "routes.orders");
        try (var runtime = new CamelRouteRuntime("active", resource, context -> { },
            builder -> builder.from_("direct:active").setBody().constant("active"))) {
            assertEquals("active", runtime.request("direct:active", "ignored"));
        }
        assertThrows(IllegalArgumentException.class, () -> CamelRouteRuntime.validate(
            new CamelRouteResource("UNKNOWN", "source", null, null)));
        assertEquals("YAML", new CamelRouteResource(null, "source", null, null).language());
    }

    private static CamelRouteResource reference() {
        return new CamelRouteResource("JYTHON", null, "MyProject", "camel_routes.orders");
    }
}
