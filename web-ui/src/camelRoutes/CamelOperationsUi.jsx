import React from "react";
import { Drawer, DrawerTemplate, DrawerTemplateSize, InlineTable } from "@inductiveautomation/ignition-web-ui";
import { Content } from "@inductiveautomation/ignition-gateway-lib";
import { FormSection, Notice, Tag } from "./CamelUi";

export function useCamelView(url) {
  const [data, setData] = React.useState();
  const [error, setError] = React.useState("");
  React.useEffect(() => {
    if (!url) { setData(undefined); setError(""); return undefined; }
    const controller = new AbortController();
    setData(undefined); setError("");
    fetch(url, { credentials: "same-origin", signal: controller.signal }).then(async response => {
      const body = await response.json();
      if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
      return body;
    }).then(body => { if (!controller.signal.aborted) setData(body); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [url]);
  return { data, error };
}

const columns = [
  { header: "KIND", fieldName: "kind" }, { header: "NAME", fieldName: "name" },
  { header: "STATE", fieldName: "state" }, { header: "RELATIONSHIP", fieldName: "relationship" },
  { header: "CONFIDENCE", fieldName: "confidence" }, { header: "REASON", fieldName: "reason" },
];
const rows = values => (values || []).map(value => ({ ...value, key: `${value.kind}:${value.name}` }));

export function DependenciesPanel({ kind, name }) {
  const [revision, refresh] = React.useReducer(value => value + 1, 0);
  const { data, error } = useCamelView(name ? `/data/apache-camel/camel-dependencies?kind=${encodeURIComponent(kind)}&name=${encodeURIComponent(name)}&view=${revision}` : null);
  return <>
    <div className="camel-toolbar"><p className="camel-hint">Static snapshot. No scripts, factories, connections or endpoints are executed.</p>
      <button className="camel-button" type="button" disabled={!data && !error} onClick={refresh}>Refresh dependencies</button></div>
    {error && <Notice error>{error}</Notice>}
    {!data && !error && <p className="camel-muted">Loading dependencies…</p>}
    {data && <>
      <p className="camel-hint">{data.impact}</p>
      <FormSection title="Required resources" trailing={<Tag>{data.requires.length}</Tag>}>
        <div className="camel-scroll"><InlineTable rowIdProp="key" columnDef={columns} data={rows(data.requires)} emptyText="No statically known dependencies." /></div>
      </FormSection>
      <FormSection title="Used by / change impact" trailing={<Tag>{data.usedBy.length}</Tag>}>
        <div className="camel-scroll"><InlineTable rowIdProp="key" columnDef={columns} data={rows(data.usedBy)} emptyText="No statically known callers. Dynamic use may still exist." /></div>
      </FormSection>
      {kind === "kamelet" && <FormSection title="Declared parameters">
        <div className="camel-scroll"><InlineTable rowIdProp="name" columnDef={[{ header: "NAME", fieldName: "name" }, { header: "TYPE", fieldName: "type" },
          { header: "REQUIRED", fieldName: "requiredText" }, { header: "DEFAULT DECLARED", fieldName: "defaultText" }]}
          data={(data.resource.parameters || []).map(value => ({ ...value, requiredText: value.required ? "Yes" : "No", defaultText: value.hasDefault ? "Yes" : "No" }))}
          emptyText="No YAML-declared parameters available. Jython builders are not executed to inspect parameters." /></div>
        <p className="camel-hint">Default values and source are deliberately omitted from this operations view.</p>
      </FormSection>}
      {data.uncertainties.length > 0 && <FormSection title="Manual checks"><ul>{data.uncertainties.map(value => <li key={value}>{value}</li>)}</ul></FormSection>}
    </>}
  </>;
}

export function ConfigurationDetails({ kind, resource, onClose }) {
  return <Drawer open={!!resource} anchor="right" onClose={onClose} size={DrawerTemplateSize.LARGE}>
    <DrawerTemplate path={[`${resource?.name || "Configuration"} Details`]} hideButtonBar onClose={onClose}>
      <Content padding="1rem"><div className="camel-ui">
        {resource && <DependenciesPanel key={`${kind}:${resource.name}`} kind={kind} name={resource.name} />}
      </div></Content>
    </DrawerTemplate>
  </Drawer>;
}
