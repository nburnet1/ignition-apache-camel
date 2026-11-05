import React from "react";
import { BaseFormControlInput, TextInput, SelectInput } from "@inductiveautomation/ignition-web-ui";
import { useConfigurationModeContext } from "@inductiveautomation/ignition-gateway-lib";
import { Tag, RemoveButton, fieldError } from "./CamelUi";

export function useOptions(url) {
  const [state, setState] = React.useState({ data: null, error: "" });
  const [version, refresh] = React.useReducer(value => value + 1, 0);
  React.useEffect(() => {
    if (!url) { setState({ data: null, error: "" }); return undefined; }
    const controller = new AbortController();
    setState(previous => ({ data: version ? previous.data : null, error: "" }));
    fetch(url, { signal: controller.signal }).then(async response => {
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
      return response.json();
    }).then(data => { if (!controller.signal.aborted) setState({ data, error: "" }); })
      .catch(error => { if (!controller.signal.aborted) setState({ data: null, error: `Choices unavailable: ${error.message}` }); });
    return () => controller.abort();
  }, [url, version]);
  return React.useMemo(() => ({ ...state, refresh }), [state]);
}

export function Field({ context, field, label, description, choices, ...props }) {
  const { getConfigPath, prefix } = useConfigurationModeContext();
  return <BaseFormControlInput context={context} id={`${prefix}-${field.replaceAll(".", "-")}`}
    name={getConfigPath(`config.${field}`)} label={label} description={description}
    input={choices ? <SelectInput values={choices} /> : <TextInput {...props} />} />;
}

export function Toggle({ context, field, label, description }) {
  const { getConfigPath } = useConfigurationModeContext();
  const path = getConfigPath(`config.${field}`);
  return <div><label className="camel-toggle"><input type="checkbox" checked={!!context.watch(path)}
    onChange={event => context.setValue(path, event.target.checked, { shouldDirty: true, shouldValidate: true })} /> {label}</label>
    {description && <p className="camel-hint">{description}</p>}</div>;
}

export function ResourceChoice({ context, field, label, kind, filter, description, optional = false, refreshToken = 0, onCreate }) {
  const { getConfigPath } = useConfigurationModeContext();
  const path = getConfigPath(`config.${field}`);
  const url = kind === "project" ? "/data/api/v1/projects/list" : kind === "TLS" ? "/data/apache-camel/camel-configuration-options"
    : "/data/api/v1/resources/names/ignition/database-connection";
  const { data, error, refresh } = useOptions(url);
  React.useEffect(() => { if (refreshToken) refresh(); }, [refreshToken, refresh]);
  let values = kind === "TLS" ? (data?.registry || []).filter(item => item.type === "TLS") : (data?.items || []);
  values = values.filter(item => kind !== "project" || !item.inheritable);
  if (filter) values = values.filter(filter);
  const current = context.watch(path);
  const choices = values.map(item => ({ value: item.name, label: `${item.name}${item.enabled === false ? " (disabled)" : ""}` }));
  if (current && !choices.some(item => item.value === current)) choices.push({ value: current, label: `${current} (unavailable)` });
  return <><Field context={context} field={field} label={label} description={description}
    choices={[{ value: "", label: optional ? "System default" : "Select…" }, ...choices]} />
    {onCreate && <button type="button" className="camel-button" onClick={onCreate}>Create certificate</button>}
    {error && <p className="camel-error" role="alert">{error}</p>}</>;
}

/** Native Secret Provider choices. Only a provider/name reference is held in the resource. */
export function SecretPicker({ value, onChange, label, optional = true, compact = false }) {
  const providers = useOptions("/data/api/v1/resources/names/ignition/secret-provider");
  const secrets = useOptions(value?.providerName ? `/data/api/v1/secret-providers/${encodeURIComponent(value.providerName)}/secrets` : null);
  const providerNames = (providers.data?.items || []).map(item => item.name);
  if (value?.providerName && !providerNames.includes(value.providerName)) providerNames.push(value.providerName);
  const secretNames = [...(secrets.data?.secrets || [])];
  if (value?.secretName && !secretNames.includes(value.secretName)) secretNames.push(value.secretName);
  return <fieldset className="camel-secret-field">
    <legend className={compact ? "camel-sr-only" : undefined}>{label}</legend>
    <div className="camel-secret">
    <label><span>Provider</span><select className="camel-select" aria-label={`${label} provider`} value={value?.providerName || ""}
      onChange={event => onChange(event.target.value ? { providerName: event.target.value, secretName: "" } : null)}>
      <option value="">{optional ? "None" : "Select provider…"}</option>{providerNames.map(name => <option key={name}>{name}</option>)}
    </select></label>
    <label><span>Secret</span><select className="camel-select" aria-label={`${label} secret`} disabled={!value?.providerName} value={value?.secretName || ""}
      onChange={event => onChange({ providerName: value.providerName, secretName: event.target.value })}>
      <option value="">Select secret…</option>{secretNames.map(name => <option key={name}>{name}</option>)}
    </select></label>
    </div>
    {(providers.error || secrets.error) && <p className="camel-error" role="alert">{providers.error || secrets.error}</p>}
    {!providers.error && providers.data && !providerNames.length && <p className="camel-hint">No secret providers configured.</p>}
  </fieldset>;
}

export function SecretField({ context, field, label }) {
  const { getConfigPath } = useConfigurationModeContext();
  const path = getConfigPath(`config.${field}`);
  const error = fieldError(context, path);
  return <><SecretPicker value={context.watch(path)} label={label}
    onChange={value => context.setValue(path, value, { shouldDirty: true, shouldValidate: true })} />
    {error && <p className="camel-error" role="alert">Select a provider and secret.</p>}</>;
}

export function SelectionList({ context, field, label, choices, orphans = true, hideLabel = false, emptyText }) {
  const [query, setQuery] = React.useState("");
  const { getConfigPath } = useConfigurationModeContext();
  const path = getConfigPath(`config.${field}`);
  const selected = context.watch(path) || [];
  const all = [...choices];
  if (orphans) selected.forEach(name => { if (!all.some(choice => choice.name === name)) all.push({ name, type: "unavailable", enabled: false }); });
  const chosen = selected.filter(name => all.some(choice => choice.name === name)).length;
  const visible = all.filter(choice => `${choice.name} ${choice.type}`.toLowerCase().includes(query.toLowerCase()));
  return <fieldset className="camel-selection"><legend className={hideLabel ? "camel-sr-only" : undefined}>{label}{!hideLabel && chosen > 0 && <span className="camel-count">{chosen}</span>}</legend>
    {all.length > 5 && <input className="camel-input camel-search" type="search" aria-label={`Filter ${label.toLowerCase()}`} placeholder="Filter by name…" value={query} onChange={event => setQuery(event.target.value)} />}
    {!all.length && <div className="camel-row-empty">{emptyText || `No ${label.toLowerCase()} configured.`}</div>}
    {all.length > 0 && <div className="camel-selection-list">{visible.map(choice => <label key={choice.name} className="camel-selection-item">
      <input type="checkbox" aria-label={`${choice.name} — ${choice.type}`} checked={selected.includes(choice.name)} onChange={event => context.setValue(path,
        event.target.checked ? [...selected, choice.name] : selected.filter(name => name !== choice.name), { shouldDirty: true, shouldValidate: true })} />
      <span>{choice.name}{choice.enabled === false && <small className="camel-error"> · Unavailable</small>}</span><Tag>{choice.type}</Tag>
    </label>)}{!visible.length && <div className="camel-row-empty">No matches.</div>}</div>}
  </fieldset>;
}

/** Row editor with stable input identities; invalid/duplicate names cannot silently overwrite values. */
export function StringMap({ context, field, label, secret = false, hideLabel = false }) {
  const { getConfigPath } = useConfigurationModeContext();
  const path = getConfigPath(`config.${field}`);
  const value = context.watch(path);
  const serial = JSON.stringify(value || {});
  const nextId = React.useRef(0);
  const lastWrite = React.useRef(null);
  const [rows, setRows] = React.useState([]);
  const [error, setError] = React.useState("");
  React.useEffect(() => {
    if (serial === lastWrite.current) return;
    if (!value || typeof value !== "object" || Array.isArray(value)) return;
    setRows(Object.entries(value).map(([key, content]) => ({ id: nextId.current++, key, content })));
    setError(""); lastWrite.current = serial;
  }, [serial]);
  const update = next => {
    setRows(next);
    const invalid = next.some(row => !row.key.trim()) || new Set(next.map(row => row.key)).size !== next.length;
    const decoded = invalid ? "__invalid_map__" : Object.fromEntries(next.map(row => [row.key, row.content]));
    lastWrite.current = JSON.stringify(decoded);
    context.setValue(path, decoded, { shouldDirty: true, shouldValidate: true });
    setError(invalid ? "Each row needs a unique, nonempty name." : "");
  };
  const validationError = fieldError(context, path);
  return <fieldset className="camel-row-editor"><legend className={hideLabel ? "camel-sr-only" : undefined}>{label}</legend>
    {!rows.length && <div className="camel-row-empty">None added.</div>}
    {rows.map((row, index) => <div key={row.id} className={`camel-row${secret ? " camel-row-secret" : ""}`}>
      <label><span className="camel-sr-only">Name</span><input className="camel-input" placeholder="Name" aria-label={`${label} name ${index + 1}`} value={row.key}
        onChange={event => update(rows.map(item => item.id === row.id ? { ...item, key: event.target.value } : item))} /></label>
      {secret ? <SecretPicker label={`${label} value ${index + 1}`} compact optional={false} value={row.content}
        onChange={content => update(rows.map(item => item.id === row.id ? { ...item, content } : item))} />
        : <label><span className="camel-sr-only">Value</span><input className="camel-input" placeholder="Value" aria-label={`${label} value ${index + 1}`} value={row.content ?? ""}
          onChange={event => update(rows.map(item => item.id === row.id ? { ...item, content: event.target.value } : item))} /></label>}
      <RemoveButton label={`Remove ${label} row ${index + 1}`} onClick={() => update(rows.filter(item => item.id !== row.id))} />
    </div>)}
    <button type="button" className="camel-button camel-button--quiet" onClick={() => update([...rows, { id: nextId.current++, key: "", content: secret ? null : "" }])}>+ Add {label.toLowerCase()}</button>
    {(error || validationError) && <p className="camel-error" role="alert">{error || validationError.message || "Complete each reference."}</p>}
  </fieldset>;
}
