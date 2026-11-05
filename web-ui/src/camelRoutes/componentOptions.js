export const optionGroup = spec => spec.secret ? "secrets" : spec.type === "object" ? "references" : "options";
export const requiresValue = spec => !!spec.required && spec.defaultValue === undefined;
export const isAdvanced = spec => `${spec.group || ""},${spec.label || ""}`.toLowerCase().includes("advanced");
export const hasOverride = (value, name, spec) => Object.prototype.hasOwnProperty.call(value[optionGroup(spec)] || {}, name);
export const initialOptionValue = spec => spec.secret ? null : String(spec.defaultValue ?? (spec.type === "boolean" ? false : ""));
export function objectReferenceChoices(spec, resources, current) {
  const registry = resources.registry || [];
  if (spec.javaType === "javax.sql.DataSource") {
    const prefix = "ignition.database/";
    const choices = (resources.databases || []).map(item => ({
      value: `${prefix}${item.name}`,
      label: `${item.name} (native Ignition database)${item.enabled === false ? " (disabled)" : ""}`,
    }));
    for (const item of registry.filter(item => item.type === "DATABASE" || item.name === current)) {
      if (!choices.some(choice => choice.value === item.name)) choices.push({
        value: item.name, label: `${item.name} (existing alias)${item.enabled === false ? " (unavailable)" : ""}`,
      });
    }
    if (current && !choices.some(choice => choice.value === current)) choices.push({
      value: current,
      label: current.startsWith(prefix) ? `${current.slice(prefix.length)} (native Ignition database) (unavailable)` : `${current} (existing alias) (unavailable)`,
    });
    return choices;
  }
  const candidates = registry.filter(item => spec.javaType?.endsWith("SSLContextParameters") ? item.type === "TLS" : item.type === "BEAN");
  if (current && !candidates.some(item => item.name === current)) candidates.push({ name: current, enabled: false });
  return candidates.map(item => ({ value: item.name, label: `${item.name}${item.enabled === false ? " (unavailable)" : ""}` }));
}
export function optionSections(schema) {
  const entries = Object.entries(schema?.componentProperties || {}).filter(([, spec]) => !spec.deprecated).sort((a, b) => (a[1].index ?? 0) - (b[1].index ?? 0));
  return { common: entries.filter(([, spec]) => requiresValue(spec) || !isAdvanced(spec)), advanced: entries.filter(([, spec]) => !requiresValue(spec) && isAdvanced(spec)) };
}
export function withoutOptionalOverrides(value, schema) {
  const next = { ...value, options: { ...value.options }, secrets: { ...value.secrets }, references: { ...value.references } };
  for (const [name, spec] of Object.entries(schema?.componentProperties || {})) if (!requiresValue(spec)) delete next[optionGroup(spec)][name];
  return next;
}
