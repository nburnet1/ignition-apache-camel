package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.util.PythonInterpreter;

class IgnitionComponentBenchmarkSyntaxTest {
    @Test
    void realGatewayFixtureCompilesWithTheSdkJythonGrammar() throws Exception {
        Path examples = Path.of(System.getProperty("camel.examplesDirectory"));
        Path fixture = examples.resolve("../../benchmarks/ignition-components/project/component_benchmark.py").normalize();
        try (var interpreter = new PythonInterpreter()) {
            interpreter.compile(Files.readString(fixture), fixture.toString());
        }
    }

    @Test
    void benchmarkControlConsumersUseThePathsCalledByTheRunner() throws Exception {
        Path examples = Path.of(System.getProperty("camel.examplesDirectory"));
        Path fixture = examples.resolve("../../benchmarks/ignition-components/project/component_benchmark.py").normalize();
        try (var interpreter = new PythonInterpreter()) {
            interpreter.exec(Files.readString(fixture));
            var builder = new JythonRoutes.Builder() {
                @Override
                public void configure() {
                    interpreter.get("configure").__call__(Py.java2py(this));
                }
            };
            builder.configure();
            var paths = builder.getRouteCollection().getRoutes().stream()
                .map(route -> route.getInput().getUri())
                .filter(uri -> uri.startsWith("ignition-gateway-http:"))
                .collect(Collectors.toSet());
            assertEquals(Set.of(
                "ignition-gateway-http:/camel-component-benchmark/echo",
                "ignition-gateway-http:/camel-component-benchmark/run",
                "ignition-gateway-http:/camel-component-benchmark/results",
                "ignition-gateway-http:/camel-component-benchmark/samples"), paths);
        }
    }
}
