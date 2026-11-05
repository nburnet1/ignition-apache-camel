import React from "react";
import { Drawer, DrawerTemplate, DrawerTemplateSize } from "@inductiveautomation/ignition-web-ui";
import { Content } from "@inductiveautomation/ignition-gateway-lib";
import { Disclosure, Notice, Tag } from "./CamelUi";

export default function CamelComponents() {
  const [open, setOpen] = React.useState(false);
  const [catalog, setCatalog] = React.useState();
  const [error, setError] = React.useState("");
  const [query, setQuery] = React.useState("");
  React.useEffect(() => {
    if (!open) return undefined;
    const controller = new AbortController();
    fetch("/data/apache-camel/camel-components", { credentials: "same-origin", signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
      }).then(body => { if (!controller.signal.aborted) { setCatalog(body); setError(""); } })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [open]);
  const entries = (catalog?.entries || []).filter(entry => `${entry.artifact} ${entry.components.join(" ")} ${entry.languages.join(" ")} ${entry.dataFormats.join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  return <>
    <div className="camel-reference"><span className="camel-muted">Component library</span>
      <button type="button" className="camel-button camel-button--quiet" onClick={() => setOpen(true)}>Browse components →</button>
    </div>
    <Drawer open={open} anchor="right" onClose={() => setOpen(false)} size={DrawerTemplateSize.LARGE}>
      <DrawerTemplate path={["Component library"]} hideButtonBar onClose={() => setOpen(false)}>
        <Content padding="1rem"><div className="camel-ui">
          <div className="camel-toolbar"><input className="camel-input" type="search" aria-label="Search components" placeholder="Search components…" value={query} onChange={event => setQuery(event.target.value)} />
            {catalog && <Tag>Camel {catalog.camelVersion}</Tag>}</div>
          {error && <Notice error>{error}</Notice>}
          {!catalog && !error && <p className="camel-muted">Loading library…</p>}
          <div className="camel-catalogue">{entries.map(entry => <article key={entry.artifact} className="camel-catalogue-entry">
            <h3>{entry.artifact}</h3>
            <div className="camel-tags">{[...entry.components, ...entry.languages.map(name => `${name} · language`), ...entry.dataFormats.map(name => `${name} · format`)].map(feature => <Tag key={feature}>{feature}</Tag>)}</div>
            <Disclosure title="Setup notes"><p className="camel-hint">{entry.setup}</p></Disclosure>
          </article>)}</div>
          {catalog && !entries.length && <p className="camel-muted">No matching components.</p>}
        </div></Content>
      </DrawerTemplate>
    </Drawer>
  </>;
}
