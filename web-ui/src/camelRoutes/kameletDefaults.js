export const KAMELET_RESOURCE_TYPE = "com.noahburnette.ignition.apachecamel/camel-kamelet";
export const KAMELET_YAML = `apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: greeting-action
  labels:
    camel.apache.org/kamelet.type: action
spec:
  definition:
    title: Greeting
    properties:
      prefix:
        type: string
        default: "Hello "
  template:
    from:
      uri: kamelet:source
      steps:
        - setBody:
            simple: '{{prefix}}\${body}'
        - to: kamelet:sink
`;
export const kameletDefaults = {
  name: "", description: "", enabled: true,
  config: { language: "YAML", yaml: KAMELET_YAML, project: "", functionPath: "", kamelets: [] },
};
