package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.script.ScriptFunction;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import com.noahburnette.ignition.apachecamel.camel.IgnitionComponents;
import com.noahburnette.ignition.apachecamel.camel.IgnitionServices;
import com.noahburnette.ignition.apachecamel.camel.InlineScript;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

class InlineScriptTest {
    @Test void validatesOnlyLambdaSyntaxWithoutExecutingAnything() {
        assertNotNull(InlineScript.source("lambda payload, meta: missingProjectFunction(payload)"));
        assertNotNull(InlineScript.source("lambda payload, meta: 1 / 0"));
        for (String invalid : new String[]{"", "x", "fn()", "lambda x: x", "lambda a,b,c: a",
            "lambda a,b=sideEffect(): a", "lambda a,*b: a", "lambda (a,b),c: a",
            "lambda a,b: (", "lambda a,b: a\nsideEffect()", "x".repeat(8193)}) {
            var error = assertThrows(IllegalArgumentException.class, () -> InlineScript.source(invalid));
            assertFalse(error.getMessage().contains("sideEffect()"));
            assertNull(error.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> InlineScript.source(null));
    }

    @Test void realJythonCompilesOncePerProducerAndExecutesWithProjectGlobals() throws Exception {
        var previous = Py.getSystemState();
        var compiles = new AtomicInteger();
        try (var python = new PythonInterpreter(null, new PySystemState())) {
            python.set("factor", 3);
            var services = new IgnitionServices() {
                public String requireTagPath(String path) { throw new UnsupportedOperationException(); }
                public AutoCloseable subscribe(String path, java.util.function.Consumer<TagValue> listener) { throw new UnsupportedOperationException(); }
                public void write(String path, Object value) { throw new UnsupportedOperationException(); }
                public Object invoke(String project, String path, Object body, Map<String, Object> headers) { throw new UnsupportedOperationException(); }
                public Transform compileInline(String project, String expression) {
                    assertEquals("MyProject", project);
                    compiles.incrementAndGet();
                    // Test-only interpreter: production uses the host project's ScriptManager.
                    python.exec(InlineScript.source(expression));
                    var callable = python.get("invoke");
                    var function = new ScriptFunction() {
                        public PyObject invoke(PyObject... args) { return callable.__call__(args); }
                        public PyObject invoke(PyObject[] args, String[] keywords) { return callable.__call__(args, keywords); }
                    };
                    return (body, headers) -> GatewayCamelServices.invokeTransform(function, body, headers);
                }
            };
            for (int generation = 1; generation <= 2; generation++) {
                try (var runtime = runtime(services, """
                    - to:
                        uri: ignition-script:inline
                        parameters:
                          project: MyProject
                          expression: "RAW{lambda payload, meta: payload * factor + meta['offset']}"
                    """)) {
                    assertEquals(generation, compiles.get());
                    for (int message = 0; message < 3; message++) {
                        assertEquals(11L, runtime.request("direct:inline", 3, Map.of("offset", 2)));
                    }
                    assertEquals(generation, compiles.get());
                }
            }
            try (var runtime = runtime(services, """
                - to:
                    uri: ignition-script:inline
                    parameters:
                      project: MyProject
                      expression: "RAW{lambda payload, meta: {'text': 'a+b&c%d#e?f', 'value': (payload + 1) % 3}}"
                """)) {
                assertEquals(Map.of("text", "a+b&c%d#e?f", "value", 2L), runtime.request("direct:inline", 4));
            }
            try (var runtime = runtime(services, """
                - to:
                    uri: ignition-script:inline
                    parameters:
                      project: MyProject
                      expression: "RAW{lambda payload, meta: meta.update({'injected': True})}"
                - setBody:
                    simple: '${header.injected}'
                """)) {
                assertNull(runtime.request("direct:inline", 4));
            }
            try (var runtime = runtime(services, """
                - to:
                    uri: ignition-script:inline
                    parameters:
                      project: MyProject
                      expression: "lambda payload, meta: None"
                - setBody:
                    constant: continued
                """)) {
                assertEquals("continued", runtime.request("direct:inline", "payload"));
            }
            try (var runtime = runtime(services, """
                - to:
                    uri: ignition-script:inline
                    parameters:
                      project: MyProject
                      expression: "lambda payload, meta: 1 / 0"
                """)) {
                assertThrows(Exception.class, () -> runtime.request("direct:inline", 1));
            }
        } finally {
            Py.setSystemState(previous);
        }
    }

    @Test void yamlValidationDoesNotCompileOrInvokeInlineCode() throws Exception {
        CamelRouteRuntime.validate(new CamelRouteResource("""
            - route:
                from:
                  uri: timer:must-not-start
                  steps:
                    - to:
                        uri: ignition-script:inline
                        parameters:
                          project: nonexistent
                          expression: "RAW{lambda payload, meta: missingFunction(payload)}"
            """));
    }

    private static CamelRouteRuntime runtime(IgnitionServices services, String steps) throws Exception {
        return new CamelRouteRuntime("inline-test", new CamelRouteResource("""
            - route:
                from:
                  uri: direct:inline
                  steps:
            """ + steps.indent(8)), new IgnitionComponents(services)::install);
    }
}
