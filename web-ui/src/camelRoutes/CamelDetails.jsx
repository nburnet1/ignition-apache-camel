import React from "react";
import {
  Button, ButtonColorClasses, Drawer, DrawerTemplate, DrawerTemplateSize,
  InlineTable, StatusBadge,
} from "@inductiveautomation/ignition-web-ui";
import { Content } from "@inductiveautomation/ignition-gateway-lib";
import { Disclosure, FormSection, Notice, Tabs, Tag } from "./CamelUi";
import { httpIngressSummary } from "./httpIngress";
import { DependenciesPanel } from "./CamelOperationsUi";
import CamelStartupError from "./CamelStartupError";
import LibraryLink from "./LibraryLink";
import { useOptions } from "./configurationFields";
import { classifySelections, groupResourceOptions, unavailableSelections } from "./libraryTabs";

const date = (value) => value ? new Date(value).toLocaleString() : "—";
const decimal = (value) => Number(value || 0).toFixed(3);
const metrics = [
  ["started", "Route visits"], ["succeeded", "Succeeded"], ["failed", "Failed"], ["inflight", "In flight"],
  ["meanProcessingMillis", "Mean latency · ms"], ["maxProcessingMillis", "Max latency · ms"],
];

export const DetailsIcon = () => (
  <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true">
    <path fill="currentColor" d="M12 5C5 5 1 12 1 12s4 7 11 7 11-7 11-7-4-7-11-7Zm0 2c4.5 0 7.8 3.6 8.6 5-.8 1.4-4.1 5-8.6 5s-7.8-3.6-8.6-5C4.2 10.6 7.5 7 12 7Zm0 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z" />
  </svg>
);

export function ResourcesSummary({ details, options, kamelets }) {
  const metadata = useOptions("/data/apache-camel/camel-component-metadata");
  const groups = groupResourceOptions(options.data, kamelets.data, metadata.data?.components);
  const classified = classifySelections(details, groups);
  const missing = options.data && kamelets.data ? unavailableSelections(details, groups) : [];
  const total = (details.components?.length || 0) + (details.registry?.length || 0) + (details.kamelets?.length || 0);
  return <FormSection title="Resources" trailing={<Tag>{total}</Tag>}>
    {(options.error || kamelets.error) && <Notice error>{options.error || kamelets.error}</Notice>}
    {metadata.error && <Notice error>{metadata.error}</Notice>}
    {!metadata.data && !metadata.error && <p className="camel-hint">Loading component categories...</p>}
    {!total && <p className="camel-hint">No Library resources selected. Routes can still use built-in components such as timer, log and the Ignition bridge.</p>}
    {classified.filter(group => !["connections", "scheduling", "component-defaults"].includes(group.id) || metadata.data).map(group => <div key={group.label} className="camel-resource-row">
      <div className="camel-resource-row-heading"><h4>{group.label}</h4><LibraryLink tab={group.id}>Open {group.label.toLowerCase()}</LibraryLink></div>
      <div className="camel-tags">{group.items.map(item => <Tag key={item.name} tone={item.enabled === false ? "error" : undefined}>{item.name}{item.enabled === false ? " \u00b7 disabled" : ""}</Tag>)}</div>
    </div>)}
    {missing.length > 0 && <div className="camel-resource-row"><div className="camel-resource-row-heading"><h4>Unavailable</h4></div>
      <div className="camel-tags">{missing.map(item => <Tag key={`${item.field}:${item.name}`} tone="error">{item.name}</Tag>)}</div>
      <p className="camel-hint">Selected by this integration but missing from the Library. Startup fails until they are recreated or deselected.</p></div>}
  </FormSection>;
}

export default function CamelDetails({ resource, onClose }) {
  const [details, setDetails] = React.useState();
  const [error, setError] = React.useState("");
  const [notice, setNotice] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [tab, setTab] = React.useState("overview");
  const panelIdPrefix = React.useId();
  const name = resource?.name;
  const httpIngress = httpIngressSummary(details);
  const options = useOptions(name ? "/data/apache-camel/camel-configuration-options" : null);
  const kamelets = useOptions(name ? "/data/apache-camel/camel-kamelets" : null);

  React.useEffect(() => {
    if (!name) return undefined;
    const controller = new AbortController();
    let timer;
    const load = async () => {
      try {
        const response = await fetch(`/data/apache-camel/camel-route-details?name=${encodeURIComponent(name)}`, {
          credentials: "same-origin", signal: controller.signal,
        });
        const body = await response.json();
        if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
        if (!controller.signal.aborted) { setDetails(body); setError(""); }
      } catch (failure) {
        if (!controller.signal.aborted) setError(failure.message);
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(load, 4000);
      }
    };
    setDetails(undefined);
    setError("");
    setNotice("");
    setTab("overview");
    load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [name]);

  const restart = async () => {
    setBusy(true);
    try {
      const sessionResponse = await fetch("/data/app/session", { credentials: "same-origin" });
      if (!sessionResponse.ok) throw new Error("Gateway session is unavailable");
      const session = await sessionResponse.json();
      const response = await fetch(`/data/apache-camel/camel-route-restart?name=${encodeURIComponent(name)}`, {
        method: "POST", credentials: "same-origin", headers: { "X-CSRF-Token": session.csrfToken },
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
      setNotice("Restart queued.");
    } catch (failure) { setNotice(`Restart failed: ${failure.message}`); }
    finally { setBusy(false); }
  };

  return <Drawer open={!!name} anchor="right" onClose={onClose} size={DrawerTemplateSize.LARGE}>
    <DrawerTemplate path={[`${name || "Camel"} Details`]} hideButtonBar onClose={onClose}>
      <Content padding="1rem">
        <div className="camel-ui">
        {error && <Notice error>{error}</Notice>}
        {!details && !error && <p className="camel-muted">Loading details…</p>}
        {notice && <Notice>{notice}</Notice>}
        {details && <>
          <div className="camel-toolbar">
           <StatusBadge type={details.state === "RUNNING" ? "success" : details.state === "ERROR" ? "error" : "default"} customLabel={details.state} />
          <Button type="button" colorClass={ButtonColorClasses.SECONDARY} onClick={restart}
            disabled={busy || !details.enabled}>Restart integration</Button>
          </div>
          {details.startupFailure?.code && <CamelStartupError failure={details.startupFailure} onDependencies={() => {
            setTab("resources");
            document.getElementById(`${panelIdPrefix}-tab-resources`)?.focus();
          }} />}
          {(details.diagnosticSummary?.issues || []).filter(issue => issue.code !== details.startupFailure?.code).map(issue => <Notice key={issue.code + issue.title} error><strong>{issue.title}</strong><p>{issue.action}</p></Notice>)}
          <Tabs label="Integration details" panelIdPrefix={panelIdPrefix} tabs={[{ id: "overview", label: "Overview" }, { id: "diagnostics", label: "Diagnostics" }, { id: "resources", label: "Resources" }]} value={tab} onChange={setTab} />
          <div role="tabpanel" aria-label="Overview" id={`${panelIdPrefix}-panel-overview`} aria-labelledby={`${panelIdPrefix}-tab-overview`} tabIndex={0} hidden={tab !== "overview"}>
          <dl className="camel-metrics">
            {metrics.map(([key, label]) => <div className="camel-metric" key={key}><dt>{label}</dt>
              <dd>{key.endsWith("Millis") ? decimal(details.metrics?.[key]) : (details.metrics?.[key] ?? 0).toLocaleString()}</dd></div>)}
          </dl>
          <FormSection title="Routes" description="Routes declared by this integration's definition, as the running context reports them." trailing={<Tag>{details.routes?.length || 0}</Tag>}>
            <div className="camel-scroll"><InlineTable rowIdProp="id" emptyText="No running routes."
              columnDef={[
                { header: "ROUTE", fieldName: "id" }, { header: "STATE", fieldName: "state" },
                { header: "SOURCE", fieldName: "sourceComponent" }, { header: "COMPLETED", fieldName: "completed" },
                { header: "FAILED", fieldName: "failed" }, { header: "IN FLIGHT", fieldName: "inflight" },
                { header: "MEAN MS", fieldName: "meanMs" }, { header: "MAX MS", fieldName: "maxMs" },
               ]} data={(details.routes || []).map(route => ({ ...route, meanMs: decimal(route.meanProcessingMillis), maxMs: decimal(route.maxProcessingMillis) }))} /></div>
            {(details.routes || []).map(route => <Disclosure key={route.id} title={route.id} summary={`${route.steps?.length || 0} ${route.steps?.length === 1 ? "step" : "steps"}`}>
              <p className="camel-hint">Caught exceptions: {route.caughtExceptions} · Last completion: {date(route.lastCompletedAt)}</p>
              <p className="camel-hint">Last success: {date(route.lastSucceededAt)} · Last failure: {date(route.lastFailedAt)} · Consecutive failed completions: {route.consecutiveFailures ?? 0}</p>
              {route.lastErrorType && <Notice error>{route.lastErrorType} · {date(route.lastErrorAt)}</Notice>}
              <div className="camel-scroll">
              <InlineTable rowIdProp="index" columnDef={[
                { header: "DEPTH", fieldName: "depth" }, { header: "STEP", fieldName: "kind" }, { header: "COMPONENT", fieldName: "component" },
              ]} data={route.steps || []} emptyText="No steps." /></div>
            </Disclosure>)}
          </FormSection>
          <ResourcesSummary details={details} options={options} kamelets={kamelets} />
          <FormSection title="HTTP access" trailing={<Tag>{httpIngress.endpoints.length}</Tag>}>
            {httpIngress.message ? <p className="camel-hint">{httpIngress.message}</p> : <>
              <div className="camel-scroll"><InlineTable rowIdProp="path" columnDef={[
                { header: "METHOD", fieldName: "method" }, { header: "REGISTERED PATH", fieldName: "path" },
              ]} data={httpIngress.endpoints} /></div>
              {!details.httpSecurityLevels?.length && <p className="camel-hint">Invocation denied: no allowed security levels selected.</p>}
            </>}
          </FormSection>
          </div>
          <div role="tabpanel" aria-label="Diagnostics" id={`${panelIdPrefix}-panel-diagnostics`} aria-labelledby={`${panelIdPrefix}-tab-diagnostics`} tabIndex={0} hidden={tab !== "diagnostics"}>
          <FormSection title="Runtime">
            <dl className="camel-facts">
              <div><dt>Language</dt><dd>{details.language}</dd></div>
              <div><dt>Generation</dt><dd>{details.diagnostics?.generation ?? "—"}</dd></div>
              <div><dt>Started</dt><dd>{date(details.startedAt)}</dd></div>
              {details.hawtioEnabled && <div><dt>External Hawtio</dt><dd>{details.hawtioScope === "jvm-readonly" ? "JVM-wide read-only inspection" : details.hawtioControlEnabled ? "Monitoring + temporary route controls" : "Monitoring only"}</dd></div>}
              {details.project && <div><dt>Project function</dt><dd>{details.project} · {details.functionPath}</dd></div>}
            </dl>
          </FormSection>
          <FormSection title="Endpoint counters">
            <div className="camel-scroll"><InlineTable rowIdProp="key" columnDef={[{ header: "COUNTER", fieldName: "key" }, { header: "VALUE", fieldName: "value" }]}
              data={Object.entries(details.diagnostics || {}).filter(([key]) => key !== "generation").map(([key, value]) => ({ key, value }))} /></div>
            <Disclosure title="About these metrics"><p className="camel-hint">Counts reflect route visits, not unique messages. Completed includes failures. Counters reset on restart.</p></Disclosure>
          </FormSection>
          <FormSection title="Recent error metadata">
            <div className="camel-scroll"><InlineTable rowIdProp="key" columnDef={[
              { header: "TIME", fieldName: "time" }, { header: "ROUTE", fieldName: "route" },
              { header: "EXCEPTION CLASS", fieldName: "type" }, { header: "COMPONENT", fieldName: "component" }, { header: "OUTCOME", fieldName: "outcome" },
            ]} data={(details.recentFailures || []).map((value, index) => ({ ...value, key: index, time: date(value.timestamp), outcome: value.handled ? "Handled exception" : "Failed completion" }))}
              emptyText="No error metadata recorded in this runtime." /></div>
            <p className="camel-hint">Last 20 route-level error observations only. No payloads, headers, exception messages, full URIs or replay controls. Nested routes may report the same failure more than once.</p>
          </FormSection>
          <p className="camel-hint">{details.diagnosticSummary?.note}</p>
          </div>
          <div role="tabpanel" aria-label="Resources" id={`${panelIdPrefix}-panel-resources`} aria-labelledby={`${panelIdPrefix}-tab-resources`} tabIndex={0} hidden={tab !== "resources"}>
            {tab === "resources" && <DependenciesPanel kind="route" name={name} />}
          </div>
        </>}
        </div>
      </Content>
    </DrawerTemplate>
  </Drawer>;
}
