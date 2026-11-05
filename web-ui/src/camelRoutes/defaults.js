export const CAMEL_RESOURCE_TYPE =
  "com.noahburnette.ignition.apachecamel/camel-route";

export const YAML_EXAMPLE = `- route:
    id: example
    from:
      uri: timer:example?period=10000
      steps:
        - setBody:
            constant: Hello from Camel
        - to: log:integration.example
`;

export const camelRouteDefaults = {
  name: "",
  description: "",
  enabled: true,
  config: {
    language: "YAML",
    yaml: YAML_EXAMPLE,
    project: "",
    functionPath: "",
    components: [],
    registry: [],
    kamelets: [],
    properties: {},
    secrets: {},
    httpSecurityLevels: [],
  },
};

export const sourceField = (language) => language === "JYTHON" ? "functionPath" : "yaml";

export function httpSecurityLevelChoices(levels = [], parent = "") {
  return levels.flatMap(level => {
    const path = parent ? `${parent}/${level.name}` : level.name;
    return [
      ...(path.startsWith("Authenticated/") ? [{ name: path, type: "Security level" }] : []),
      ...httpSecurityLevelChoices(level.children || [], path),
    ];
  });
}
