package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

class KameletExampleTest {
    private static String definition() throws Exception {
        return Files.readString(Path.of(System.getProperty("camel.examplesDirectory"), "kamelets/normalize-temperature-action.kamelet.yaml"));
    }
    private static CamelKameletResource yaml(String source, String... dependencies) {
        return new CamelKameletResource("YAML", source, null, null, List.of(dependencies));
    }
    @Test void resourceYamlLoadsTwoIndependentInstancesWithoutAClasspathDefinitionJar() throws Exception {
        var writes = new LinkedBlockingQueue<String>();
        var invocations = new java.util.ArrayList<Map<String, Object>>();
        var services = new IgnitionServices() {
            public String requireTagPath(String path) { return path; }
            public AutoCloseable subscribe(String path, Consumer<TagValue> listener) { throw new AssertionError(); }
            public void write(String path, Object value) { writes.add(value.toString()); }
            public Object invoke(String project, String function, Object body, Map<String, Object> headers) {
                assertEquals("CamelExamples", project);
                assertEquals("kamelets.normalize", function);
                assertEquals("80", headers.get("kameletWarningC"));
                invocations.add(Map.copyOf(headers));
                double value = ((Number) body).doubleValue();
                double celsius = "F".equals(headers.get("kameletInputUnit")) ? (value - 32) * 5 / 9 : value;
                return Map.of("machine", headers.get("kameletMachine"), "temperatureC", celsius,
                    "status", celsius >= 80 ? "HIGH" : "NORMAL");
            }
        };
        var source = Files.readString(Path.of(System.getProperty("camel.examplesDirectory"), "kamelet-temperature.yaml"))
            .replace("ignition-tag:[default]CamelExamples/Kamelets/CelsiusInput?includeInitial=false", "direct:celsius")
            .replace("ignition-tag:[default]CamelExamples/Kamelets/FahrenheitInput?includeInitial=false", "direct:fahrenheit");
        CamelKamelets.validate(yaml(definition()));
        var resource = new CamelRouteResource(source);
        CamelRouteRuntime.validate(resource);
        assertTrue(invocations.isEmpty());
        String definition = definition();
        assertNull(getClass().getClassLoader().getResource("kamelets/normalize-temperature-action.kamelet.yaml"));
        try (var runtime = new CamelRouteRuntime("native-kamelets", resource, context -> {
            new IgnitionComponents(services).install(context);
            try { CamelKamelets.loadYaml((DefaultCamelContext) context, "normalize-temperature-action", definition); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        })) {
            runtime.request("direct:celsius", 30.0, Map.of("ignitionTagGood", true));
            runtime.request("direct:fahrenheit", 86.0, Map.of("ignitionTagGood", true));
            runtime.request("direct:fahrenheit", 194.0, Map.of("ignitionTagGood", true));
            var values = writes.stream().map(com.inductiveautomation.ignition.common.gson.JsonParser::parseString)
                .map(value -> value.getAsJsonObject()).toList();
            assertEquals(3, values.size());
            assertEquals("Press01", values.get(0).get("machine").getAsString());
            assertEquals("Press02", values.get(1).get("machine").getAsString());
            assertEquals(30.0, values.get(0).get("temperatureC").getAsDouble());
            assertEquals(30.0, values.get(1).get("temperatureC").getAsDouble());
            assertEquals("HIGH", values.get(2).get("status").getAsString());
            assertEquals(List.of("C", "F", "F"), invocations.stream().map(row -> row.get("kameletInputUnit")).toList());
        }
    }
    @Test void yamlValidationRejectsRoutesAndInvalidDocumentsButDoesNotRequireRuntimeDependencies() throws Exception {
        assertThrows(Exception.class, () -> CamelKamelets.validate(yaml("- route:\n    from: direct:bad")));
        assertThrows(Exception.class, () -> CamelKamelets.validate(yaml("kind: [")));
        assertThrows(Exception.class, () -> CamelKamelets.validate(yaml(definition().replace("uri: kamelet:source", "uri: [not-a-uri]"))));
        // Referenced script existence and consumers are not looked up during validation.
        CamelKamelets.validate(yaml(definition().replace("kamelets.normalize", "missing.userFunction")));
        var reference = new CamelKameletResource("JYTHON", "invalid inactive draft", "MyProject", "templates.build", null);
        CamelKamelets.validate(reference);
        assertFalse(new CamelKameletResource("JYTHON", null, "", "templates.build()", null).validationErrors().isEmpty());
        assertTrue(new CamelRouteResource("- route: []").kamelets().isEmpty());
    }
    @Test void resourceNameIsAuthoritativeAndDuplicateTemplatesAreRejected() throws Exception {
        try (var context = CamelRouteRuntime.newValidationContext()) {
            CamelKamelets.loadYaml(context, "renamed-action", definition());
            assertNotNull(context.getRouteTemplateDefinition("renamed-action"));
            assertNull(context.getRouteTemplateDefinition("normalize-temperature-action"));
            assertTrue(context.getRouteDefinitions().isEmpty());
            assertThrows(Exception.class, () -> CamelKamelets.loadYaml(context, "renamed-action", definition()));
        }
        assertFalse(CamelKameletResource.validName("source"));
        assertFalse(CamelKameletResource.validName("Bad Name"));
    }
    @Test void selectionIsExplicitTransitiveAndRejectsCyclesMissingAndDisabledDefinitions() throws Exception {
        var leaf = new CamelKamelets.Definition("leaf", yaml(definition()), true);
        var parent = new CamelKamelets.Definition("parent", yaml(definition(), "leaf"), true);
        var unused = new CamelKamelets.Definition("unused", new CamelKameletResource("JYTHON", null, "X", "bad.build", null), true);
        assertEquals(List.of(leaf, parent), CamelKamelets.resolve(List.of("parent", "leaf"), Map.of("parent", parent, "leaf", leaf, "unused", unused)));
        assertThrows(IllegalStateException.class, () -> CamelKamelets.resolve(List.of("parent"), Map.of("parent", parent)));
        assertThrows(IllegalStateException.class, () -> CamelKamelets.resolve(List.of("leaf"), Map.of("leaf", new CamelKamelets.Definition("leaf", leaf.config(), false))));
        var cycle = Map.of("parent", parent, "leaf", new CamelKamelets.Definition("leaf", yaml(definition(), "parent"), true));
        assertThrows(IllegalArgumentException.class, () -> CamelKamelets.resolve(List.of("parent"), cycle));
        assertEquals(java.util.Set.of("parent", "leaf"), CamelKamelets.references(List.of("parent"), Map.of("parent", parent)));
    }
    @Test void jythonBuildsNativeTemplatesAndRetainsTheBorrowedProjectNamespace() throws Exception {
        var caller = Py.getSystemState();
        try (var project = new PythonInterpreter(null, new PySystemState())) {
            project.getSystemState().setClassLoader(getClass().getClassLoader());
            project.exec("""
                def greeting(builder):
                    builder.template().templateParameter("prefix", "Hello ")
                    builder.from_("kamelet:source").setBody().simple("{{prefix}}${body}").to("kamelet:sink")
                """);
            var before = Py.getSystemState();
            try (var runtime = new CamelRouteRuntime("jython-template", new CamelRouteResource("""
                - route:
                    from:
                      uri: direct:test
                      steps:
                        - to: kamelet:greeting/one?prefix=RAW(Welcome )
                """), context -> {
                    try { JythonKamelets.load((DefaultCamelContext) context, "greeting", builder -> project.get("greeting").__call__(Py.java2py(builder))); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }, null, () -> {}, getClass().getClassLoader(), true)) {
                assertEquals("Welcome Camel", runtime.request("direct:test", "Camel"));
            }
            assertSame(before, Py.getSystemState());
            assertNotNull(project.get("greeting"));
        } finally { Py.setSystemState(caller); }
        try (var context = CamelRouteRuntime.newValidationContext()) {
            assertThrows(IllegalArgumentException.class, () -> JythonKamelets.load(context, "bad", builder -> builder.from("timer:must-not-run").to("log:bad")));
            assertFalse(context.isStarted());
        }
    }
}
