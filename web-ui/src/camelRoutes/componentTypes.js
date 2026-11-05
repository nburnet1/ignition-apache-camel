const focused = [
  { value: "HTTP", label: "HTTP", scheme: "http" },
  { value: "HTTPS", label: "HTTPS", scheme: "https" },
  { value: "MQTT5", label: "MQTT 5", scheme: "paho-mqtt5" },
  { value: "QUARTZ", label: "Quartz", scheme: "quartz" },
];
const reserved = new Set(["ignition-tag", "ignition-script", "ignition-gateway-http", "platform-http", "properties"]);
export const componentTypeValue = config => config?.type === "GENERIC" ? config.generic?.scheme ? `generic:${config.generic.scheme}` : "" : config?.type || "HTTPS";
export function componentCategory(config, schemas = []) {
  const scheme = config?.generic?.scheme || config?.scheme || focused.find(item => item.value === config?.type)?.scheme;
  const component = schemas.find(item => item.component.scheme.split(",").includes(scheme))?.component;
  if (component?.label?.split(",").map(label => label.trim()).includes("scheduling")) return "scheduling";
  return component?.remote === true ? "connections" : "component-defaults";
}
export function componentTypeChoices(schemas, config, category) {
  const installed = new Set(schemas.flatMap(item => item.component.scheme.split(",")));
  const choices = focused.filter(item => installed.has(item.scheme));
  const known = new Set([...reserved, ...focused.map(item => item.scheme)]);
  for (const { component } of schemas) {
    for (const scheme of component.scheme.split(",")) if (!known.has(scheme)) {
      choices.push({ value: `generic:${scheme}`, label: `${component.title} · ${scheme}`, scheme });
      known.add(scheme);
    }
  }
  const filtered = category ? choices.filter(item => componentCategory(item, schemas) === category) : choices;
  const current = config?.type ? componentTypeValue(config) : "";
  if (current && !filtered.some(item => item.value === current)) {
    const focusedType = focused.find(item => item.value === current);
    const scheme = config?.generic?.scheme || focusedType?.scheme;
    filtered.push({ value: current, label: `${focusedType?.label || scheme || current} (${installed.has(scheme) ? "generic configuration" : "unavailable"})`, scheme });
  }
  return filtered.sort((a, b) => a.label.localeCompare(b.label));
}
