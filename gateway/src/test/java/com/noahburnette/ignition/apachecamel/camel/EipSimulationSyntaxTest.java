package com.noahburnette.ignition.apachecamel.camel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.python.util.PythonInterpreter;

class EipSimulationSyntaxTest {
    @Test
    void allProjectLibrariesCompileWithTheGatewaysJythonGrammar() throws Exception {
        Path directory = Path.of(System.getProperty("camel.examplesDirectory"), "simulation");
        try (var interpreter = new PythonInterpreter(); var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".py")).toList()) {
                // Compile only: never import factory dependencies or run user code.
                interpreter.compile(Files.readString(file), file.toString());
            }
        }
    }
}
