package com.noahburnette.ignition.apachecamel.camel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative static impact only: never executes scripts, constructs endpoints or exposes route source. */
public final class CamelExtensionImpact {
    private CamelExtensionImpact() { }
    public record ComponentUse(String name, String type, String scheme, boolean enabled) { }
    public record RouteInput(String name, String state, boolean enabled, String language, String yaml,
        List<String> components, boolean projectFactories) { }
    public record RouteUse(String name, String state, boolean enabled, String reason) { }
    public record Result(List<ComponentUse> components, List<RouteUse> routes, List<RouteUse> possibleRoutes) { }

    public static Result analyze(List<ComponentUse> components, List<RouteInput> routes,
        Set<String> schemes, Set<String> features, boolean unknownLibrary) {
        var affected = components.stream().filter(component -> schemes.contains(component.scheme())).toList();
        var names = affected.stream().map(ComponentUse::name).collect(java.util.stream.Collectors.toSet());
        var direct = new ArrayList<RouteUse>(); var possible = new ArrayList<RouteUse>();
        var endpointNames = new java.util.HashSet<>(schemes); endpointNames.addAll(names);
        for (var route : routes) {
            String source = route.yaml() == null ? "" : route.yaml();
            boolean selected = route.components().stream().anyMatch(names::contains);
            boolean literal = source.length() <= 1048576 && endpointNames.stream().anyMatch(scheme ->
                Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(scheme) + ":").matcher(source).find());
            boolean format = source.length() <= 1048576 && features.stream().anyMatch(feature ->
                Pattern.compile("(?im)(?:\\b" + Pattern.quote(feature) + "\\s*:|\\blibrary\\s*:\\s*['\"]?" + Pattern.quote(feature) + "\\b)").matcher(source).find());
            if (selected || literal || format) {
                direct.add(new RouteUse(route.name(), route.state(), route.enabled(), selected ? "Selected component dependency"
                    : format ? "Data format or language reference" : "Literal endpoint reference (conservative scan)"));
            } else if (unknownLibrary || route.projectFactories() || "JYTHON".equals(route.language())
                || source.length() > 1048576 || source.contains("{{") || source.contains("${")
                || source.contains("ignition-script:") || source.contains("bean:") || source.contains("toD:")
                || source.contains("recipientList:") || source.contains("process:")) {
                possible.add(new RouteUse(route.name(), route.state(), route.enabled(), unknownLibrary ? "Library dependencies are unknown"
                    : "Project code or dynamic endpoints cannot be resolved statically"));
            }
        }
        return new Result(affected, List.copyOf(direct), List.copyOf(possible));
    }
}
