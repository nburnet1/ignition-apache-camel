package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CamelExtensionImpactTest {
    private static CamelExtensionImpact.RouteInput route(String name, String language, String yaml, List<String> components, boolean factories) {
        return new CamelExtensionImpact.RouteInput(name, "RUNNING", true, language, yaml, components, factories);
    }
    @Test void distinguishesSelectedLiteralDynamicAndUnrelatedReferencesWithoutLeakingSource() {
        var component = new CamelExtensionImpact.ComponentUse("plant-http", "HTTPS", "https", false);
        var result = CamelExtensionImpact.analyze(List.of(component), List.of(
            route("selected", "YAML", "", List.of("plant-http"), false),
            route("literal", "YAML", "to: https://user:super-secret@example.test", List.of(), false),
            route("script", "JYTHON", "", List.of(), false),
            route("dynamic", "YAML", "toD: '{{destination}}'", List.of(), false),
            route("native", "YAML", "from: ignition-gateway-http:/echo", List.of(), false)
        ), Set.of("https"), Set.of(), false);
        assertEquals(List.of("selected", "literal"), result.routes().stream().map(CamelExtensionImpact.RouteUse::name).toList());
        assertEquals(List.of("script", "dynamic"), result.possibleRoutes().stream().map(CamelExtensionImpact.RouteUse::name).toList());
        assertEquals(1, result.components().size());
        assertFalse(result.toString().contains("super-secret")); assertFalse(result.toString().contains("example.test"));
    }
    @Test void includesDataFormatsSchedulersAndFactories() {
        var result = CamelExtensionImpact.analyze(List.of(), List.of(
            route("json", "YAML", "library: Jackson", List.of(), false),
            route("scheduler", "YAML", "from: quartz:tick", List.of(), false),
            route("factory", "YAML", "", List.of(), true)
        ), Set.of("quartz"), Set.of("jackson"), false);
        assertEquals(2, result.routes().size()); assertEquals("factory", result.possibleRoutes().get(0).name());
    }
    @Test void unknownLibraryImpactsAreExplicitlyUncertain() {
        var result = CamelExtensionImpact.analyze(List.of(), List.of(route("native", "YAML", "from: ignition-gateway-http:/echo", List.of(), false)), Set.of(), Set.of(), true);
        assertTrue(result.routes().isEmpty()); assertEquals(1, result.possibleRoutes().size());
    }
}
