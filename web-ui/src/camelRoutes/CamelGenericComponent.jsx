import React from "react";
import { useConfigurationModeContext } from "@inductiveautomation/ignition-gateway-lib";
import { SecretPicker, useOptions } from "./configurationFields";
import { Disclosure, FormSection, Notice, Tag, ValidationMessage, fieldError } from "./CamelUi";
import { hasOverride, initialOptionValue, objectReferenceChoices, optionGroup, optionSections, requiresValue, withoutOptionalOverrides } from "./componentOptions";

function Setting({ name, spec, value, context, resources, onChange }) {
  const { getConfigPath } = useConfigurationModeContext();
  const group = optionGroup(spec), required = requiresValue(spec), overridden = hasOverride(value, name, spec);
  const active = required || overridden;
  const path = getConfigPath(`config.generic.${group}.${name}`);
  const label = spec.displayName || name;
  const update = content => onChange({ ...value, [group]: { ...value[group], [name]: content } });
  const reset = () => { const next = { ...value, [group]: { ...value[group] } }; delete next[group][name]; onChange(next); };
  const current = value[group]?.[name];
  const choices = spec.enum ? spec.enum.map(String) : spec.type === "boolean" ? ["true", "false"] : null;
  const database = spec.javaType === "javax.sql.DataSource";
  const referenceChoices = group === "references" ? objectReferenceChoices(spec, resources, current) : [];
  const description = spec.description || "";
  return <div className={`camel-setting${active ? " is-overridden" : ""}`}>
    <div className="camel-setting-description"><strong>{label}</strong>{required && <Tag>Required</Tag>}
      {description.length <= 240 ? <p className="camel-hint">{description}</p> : <details className="camel-setting-help"><summary>{description.split(/(?<=\.)\s/)[0]}</summary><p className="camel-hint">{description}</p></details>}
    </div>
    <div className="camel-setting-value">
      {!required && <label className="camel-toggle camel-setting-toggle"><input type="checkbox" checked={overridden} aria-label={`Override ${label}`}
        onChange={event => event.target.checked ? update(initialOptionValue(spec)) : reset()} />Override default</label>}
      {!active ? <div className="camel-setting-default"><span>Default</span><strong>{spec.defaultValue === undefined ? "Component default" : String(spec.defaultValue)}</strong></div>
        : spec.secret ? <SecretPicker value={current} label={label} optional={false} onChange={update} />
          : group === "references" ? <><select className="camel-select" aria-label={database ? `${label} — Native Ignition database connection` : label} value={current || ""} onChange={event => update(event.target.value)}>
            <option value="">{database ? "Select native Ignition database connection…" : "Select registry object…"}</option>{referenceChoices.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>{database && <p className="camel-hint">Native Ignition database connection. Configure databases in Ignition; the connection is resolved when used.</p>}</>
            : choices ? <select className="camel-select" aria-label={label} value={current ?? ""} onChange={event => update(event.target.value)}>
              <option value="" disabled>Select a value…</option>{choices.map(choice => <option key={choice} value={choice}>{choice}</option>)}
            </select>
              : <input className="camel-input" aria-label={label} type={["integer", "number"].includes(spec.type) ? "number" : "text"}
                step={spec.type === "integer" ? "1" : "any"} value={current ?? ""} onChange={event => update(event.target.value)} />}
      <ValidationMessage context={context} path={path} />
    </div>
  </div>;
}

export default function CamelGenericComponent({ context }) {
  const { getConfigPath } = useConfigurationModeContext();
  const { data, error } = useOptions("/data/apache-camel/camel-component-metadata");
  const registry = useOptions("/data/apache-camel/camel-configuration-options");
  const value = context.watch(getConfigPath("config.generic")) || {};
  const schema = data?.components.find(item => item.component.scheme.split(",").includes(value.scheme));
  const databases = useOptions(Object.values(schema?.componentProperties || {}).some(spec => optionGroup(spec) === "references" && spec.javaType === "javax.sql.DataSource" && !spec.deprecated)
    ? "/data/api/v1/resources/names/ignition/database-connection" : null);
  const sections = optionSections(schema);
  const overrides = Object.entries(schema?.componentProperties || {}).filter(([name, spec]) => !requiresValue(spec) && hasOverride(value, name, spec)).length;
  const write = next => context.setValue(getConfigPath("config.generic"), next, { shouldDirty: true, shouldValidate: true });
  const resources = { registry: registry.data?.registry, databases: databases.data?.items };
  const render = ([name, spec]) => <Setting key={name} name={name} spec={spec} value={value} context={context} resources={resources} onChange={write} />;
  const advancedInvalid = sections.advanced.some(([name, spec]) => fieldError(context, getConfigPath(`config.generic.${optionGroup(spec)}.${name}`)));
  const unknown = ["options", "secrets", "references"].flatMap(group => Object.keys(value[group] || {}).filter(name => schema && (!schema.componentProperties?.[name] || schema.componentProperties[name].deprecated)).map(name => [name, { type: group === "references" ? "object" : "string", secret: group === "secrets", description: "Not supported by the installed component metadata. Remove this setting." }]));
  return <FormSection title="Shared component defaults" trailing={overrides > 0 && <Tag>{overrides} {overrides === 1 ? "override" : "overrides"}</Tag>}>
    {error && <Notice error>{error}</Notice>}
    {databases.error && <Notice error>{databases.error}</Notice>}
    {!data && !error && <p className="camel-muted" role="status">Loading component settings…</p>}
    <ValidationMessage context={context} path={getConfigPath("config.generic")} />
    {schema && <>
      <p className="camel-hint">Settings here apply to every endpoint using this named component. Destinations and per-message options stay in your route.</p>
      {sections.common.length ? <div className="camel-settings-list">{sections.common.map(render)}</div>
        : <div className="camel-row-empty">No connection settings are required. This component is ready to use with its defaults.</div>}
      {sections.advanced.length > 0 && <Disclosure title="Advanced settings" summary={`${sections.advanced.length} ${sections.advanced.length === 1 ? "setting" : "settings"}`}
        defaultOpen={sections.advanced.some(([name, spec]) => hasOverride(value, name, spec))} invalid={advancedInvalid}>
        {sections.advanced.map(render)}
      </Disclosure>}
      {unknown.length > 0 && <Disclosure title="Unrecognized settings" summary={unknown.length} defaultOpen invalid>{unknown.map(render)}</Disclosure>}
      {overrides > 0 && <button type="button" className="camel-button camel-button--quiet" onClick={() => write(withoutOptionalOverrides(value, schema))}>Reset optional overrides</button>}
    </>}
    {!schema && value.scheme && data && <Notice error>Metadata is unavailable. Install the component’s JARs and restart the Gateway.</Notice>}
  </FormSection>;
}
