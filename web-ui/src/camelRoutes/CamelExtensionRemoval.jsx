import React from "react";
import { Drawer, DrawerTemplate, DrawerTemplateSize } from "@inductiveautomation/ignition-web-ui";
import { Content } from "@inductiveautomation/ignition-gateway-lib";
import { Disclosure, FormSection, Notice, Tag } from "./CamelUi";

function ResourceList({ title, items, components = false }) {
  return <FormSection title={title} trailing={<Tag>{items.length}</Tag>}>
    {!items.length ? <p className="camel-muted">None detected.</p> : <ul className="camel-impact-resources">{items.map(item => <li key={item.name}>
      <div><strong>{item.name}</strong><small>{components ? item.scheme : item.reason}{item.enabled === false ? " · Disabled definition" : ""}</small></div>
      <Tag>{components ? item.enabled ? "Enabled" : "Disabled" : item.state}</Tag>
    </li>)}</ul>}
  </FormSection>;
}

export default function CamelExtensionRemoval({ id, busy, onClose, onRemove }) {
  const [plan, setPlan] = React.useState();
  const [cleanup, setCleanup] = React.useState(false);
  const [confirmed, setConfirmed] = React.useState(false);
  const [error, setError] = React.useState("");
  const [reload, setReload] = React.useState(0);
  React.useEffect(() => { if (plan && !plan.cleanupSafe && cleanup) setCleanup(false); }, [plan, cleanup]);
  React.useEffect(() => { setCleanup(false); setConfirmed(false); setError(""); }, [id]);
  React.useEffect(() => {
    setPlan(undefined); setConfirmed(false);
    if (!id) return undefined;
    const controller = new AbortController();
    fetch(`/data/apache-camel/camel-extension-removal-preview?id=${encodeURIComponent(id)}&includeUnused=${cleanup}`, { signal: controller.signal })
      .then(async response => { const body = await response.json(); if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`); return body; })
      .then(body => { if (!controller.signal.aborted) setPlan(body); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [id, cleanup, reload]);
  const submit = async () => {
    setError("");
    try { await onRemove(plan); }
    catch (failure) { setError(failure.message); setConfirmed(false); if (failure.status === 409) setReload(value => value + 1); }
  };
  return <Drawer open={!!id} anchor="right" onClose={() => { if (!busy) onClose(); }} size={DrawerTemplateSize.LARGE}>
    <DrawerTemplate path={[plan ? `${plan.title} — removal impact` : "Review removal impact"]} hideButtonBar onClose={() => { if (!busy) onClose(); }}>
      <Content padding="1rem"><div className="camel-ui camel-impact">
        {error && <Notice error>{error}</Notice>}
        {!plan && <p className="camel-muted">Checking dependencies and route references…</p>}
        {plan && <>
          <Notice>Removal applies after Gateway restart. Existing definitions are kept; routes requiring a removed library may fail to start.</Notice>
          <FormSection title="Removal plan" trailing={<Tag>{plan.remove.length} {plan.remove.length === 1 ? "JAR" : "JARs"}</Tag>}>
            <ul className="camel-group-libraries">{plan.remove.map(file => <li key={file.id}>{file.name}</li>)}</ul>
            {plan.dependenciesKnown && <label className="camel-toggle"><input type="checkbox" checked={cleanup} disabled={busy || !plan.cleanupSafe}
              onChange={event => setCleanup(event.target.checked)} />Also remove supporting libraries not needed by another group</label>}
            {!plan.cleanupSafe && <p className="camel-hint">Supporting libraries are kept to avoid breaking other extensions.{plan.cleanupBlockers?.length > 0 && ` Unknown or changed dependency sets: ${plan.cleanupBlockers.join(", ")}.`}</p>}
            {plan.retain.length > 0 && <Disclosure title="Supporting libraries kept" summary={plan.retain.length}><ul className="camel-group-libraries">{plan.retain.map(file => <li key={file.id}>{file.name}</li>)}</ul></Disclosure>}
            {plan.dependentGroups.length > 0 && <Notice error>Other groups depend on this library: {plan.dependentGroups.join(", ")}.</Notice>}
          </FormSection>
          <ResourceList title="Configured components" items={plan.components} components />
          <ResourceList title="Routes with detected references" items={plan.routes} />
          {plan.possibleRoutes.length > 0 && <Disclosure title="Routes that may also be affected" summary={`${plan.possibleRoutes.length} script/dynamic references`}>
            <ResourceList title="Potential impact" items={plan.possibleRoutes} />
          </Disclosure>}
          <p className="camel-hint">Static preview cannot prove the absence of runtime uses from project code or external Gateway scripts.</p>
          <div className="camel-removal-confirm"><label className="camel-toggle"><input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />I have reviewed the dependencies and resource impact.</label>
          <div className="camel-extension-actions"><button type="button" className="camel-button camel-button--danger" disabled={!confirmed || busy} onClick={submit}>{busy ? "Scheduling…" : "Schedule removal"}</button>
            <button type="button" className="camel-button camel-button--quiet" disabled={busy} onClick={onClose}>Cancel</button></div>
          </div>
        </>}
      </div></Content>
    </DrawerTemplate>
  </Drawer>;
}
