import React from "react";
import { BaseFormControlInput, TextInput, yup } from "@inductiveautomation/ignition-web-ui";
import { StepWatcher, useConfigurationModeContext, useFormValidation } from "@inductiveautomation/ignition-gateway-lib";
import { enabledCell } from "../resourceCells";
import CamelGenericComponent from "./CamelGenericComponent";
import { Field, ResourceChoice, SecretField, StringMap, Toggle, useOptions } from "./configurationFields";
import { componentTypeChoices, componentTypeValue } from "./componentTypes";
import { ChoiceGroup, Disclosure, FormSection, Notice, Tag, ValidationMessage, fieldError } from "./CamelUi";
import { MODULE_ID } from "./libraryTabs";
import NestedResourceEditor from "./NestedResourceEditor";

export const COMPONENT_RESOURCE_TYPE = `${MODULE_ID}/camel-component`;
export const REGISTRY_RESOURCE_TYPE = `${MODULE_ID}/camel-registry`;
export const resourceDefaults = config => ({ name: "", description: "", enabled: true, config });
const reference = yup.object({ providerName: yup.string().required(), secretName: yup.string().required() }).nullable().default(null);
const stringMap = yup.object().default({});
export const componentDefaults = type => ({ type,
  generic: type === "GENERIC" ? { scheme: "", options: {}, secrets: {}, references: {} } : null,
  http: type === "HTTP" || type === "HTTPS" ? { connectTimeout: 5000, responseTimeout: 30000, tls: "" } : null,
  mqtt: type === "MQTT5" ? { brokerUrl: "tcp://localhost:1883", username: "", password: null, tls: "", qos: 1, cleanStart: true, connectionTimeout: 10, sessionExpiryInterval: 0 } : null,
  quartz: type === "QUARTZ" ? { threads: 2 } : null,
});
export const registryDefaults = type => ({ type,
  bean: type === "BEAN" ? { project: "", functionPath: "", arguments: {}, closeOnStop: true } : null,
  tls: type === "TLS" ? { storeType: "PKCS12", protocol: "TLS", trustStore: null, trustStorePassword: null, keyStore: null, keyStorePassword: null, privateKeyPassword: null } : null,
  database: type === "DATABASE" ? "" : null,
});
const componentSchema = yup.object({
  type: yup.string().oneOf(["HTTP", "HTTPS", "MQTT5", "QUARTZ", "GENERIC"]).required(),
  generic: yup.object({ scheme: yup.string().required("Select an installed component"), options: stringMap,
    secrets: yup.object().test("references", "Select a provider and secret for each option", value => Object.values(value || {}).every(ref => ref?.providerName && ref?.secretName)),
    references: yup.object().test("references", "Select a registry entry for each object option", value => Object.values(value || {}).every(ref => !!ref)) }).nullable().default(null),
  http: yup.object({ connectTimeout: yup.number().integer().min(1).max(300000).required(), responseTimeout: yup.number().integer().min(1).max(300000).required(), tls: yup.string().nullable() }).nullable().default(null),
  mqtt: yup.object({ brokerUrl: yup.string().required(), username: yup.string().nullable(), password: reference,
    tls: yup.string().nullable(), qos: yup.number().integer().min(0).max(2).required(), connectionTimeout: yup.number().integer().min(1).max(300).required(), cleanStart: yup.boolean(),
    sessionExpiryInterval: yup.number().integer().min(0).max(4294967295).required() }).nullable().default(null),
  quartz: yup.object({ threads: yup.number().integer().min(1).max(16).required() }).nullable().default(null),
});
const registrySchema = yup.object({
  type: yup.string().oneOf(["BEAN", "TLS", "DATABASE"]).required(),
  bean: yup.object({ project: yup.string().required(), functionPath: yup.string().required().matches(/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/),
    arguments: stringMap, closeOnStop: yup.boolean() }).nullable().default(null),
  tls: yup.object({ storeType: yup.string().oneOf(["PKCS12", "JKS"]), protocol: yup.string().oneOf(["TLS", "TLSv1.2", "TLSv1.3"]),
    trustStore: reference, trustStorePassword: reference, keyStore: reference, keyStorePassword: reference, privateKeyPassword: reference }).nullable().default(null),
  database: yup.string().nullable().when("type", { is: "DATABASE", then: schema => schema.required("Select an Ignition database connection") }),
});
const formSchema = (config, component) => yup.object({
  name: yup.string().required().matches(component ? /^[a-z][a-z0-9-]{0,63}$/ : /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/,
    component ? "Use 1–64 lowercase letters/digits/hyphens, starting with a letter." : "Use a name without spaces or special characters."),
  config: config.required(), backupConfig: config.nullable().default(null),
});
const componentFormSchema = formSchema(componentSchema, true);
const registryFormSchema = formSchema(registrySchema, false);

function General({ context, type, component }) {
  const { prefix } = useConfigurationModeContext();
  const formId = React.useId();
  return <>
    {type !== "edit" && <BaseFormControlInput context={context} id={`${prefix}-${formId}-name`} name="name" label="Name *"
      input={<TextInput placeholder={component ? "e.g. orders-http" : "e.g. orderValidator"} />} />}
    <BaseFormControlInput context={context} id={`${prefix}-${formId}-description`} name="description" label="Description" input={<TextInput placeholder="Optional" />} />
  </>;
}
function TypeSelector({ context, choices, defaults, label }) {
  const { getConfigPath } = useConfigurationModeContext();
  const root = getConfigPath("config");
  const drafts = React.useRef({});
  const config = context.watch(root) || {};
  return <ChoiceGroup label={label} value={context.watch(`${root}.type`) || choices[0][0]}
    choices={choices.map(([value, text]) => ({ value, label: text }))}
    onChange={value => {
      drafts.current[`${root}:${config.type}`] = config;
      context.setValue(root, drafts.current[`${root}:${value}`] || defaults(value), { shouldDirty: true, shouldValidate: true });
    }} />;
}
export function ComponentForm({ context, type, category }) {
  useFormValidation(componentFormSchema);
  const { getConfigPath } = useConfigurationModeContext();
  const kind = context.watch(getConfigPath("config.type")) || "HTTPS";
  const config = context.watch(getConfigPath("config")) || {};
  const metadata = useOptions("/data/apache-camel/camel-component-metadata");
  const drafts = React.useRef({});
  const [creatingCertificate, setCreatingCertificate] = React.useState(false);
  const [certificateRevision, refreshCertificates] = React.useReducer(value => value + 1, 0);
  const certificateChoice = { refreshToken: certificateRevision, onCreate: () => setCreatingCertificate(true) };
  const selectType = value => {
    const root = getConfigPath("config");
    drafts.current[`${root}:${componentTypeValue(config)}`] = config;
    const next = drafts.current[`${root}:${value}`] || (value.startsWith("generic:")
      ? { ...componentDefaults("GENERIC"), generic: { scheme: value.substring(8), options: {}, secrets: {}, references: {} } }
      : componentDefaults(value));
    context.setValue(root, next, { shouldDirty: true, shouldValidate: true });
  };
  const invalid = field => !!fieldError(context, getConfigPath(`config.${field}`));
  return <StepWatcher validateOnRender={type === "edit"}>
    <div className="camel-ui camel-form">
    <FormSection title={category === "scheduling" ? "Scheduler" : category === "component-defaults" ? "Component defaults" : "Connection"}>
      <label className="camel-type-field"><span className="camel-control-label">Component type *</span><select className="camel-select" aria-label="Component type" value={componentTypeValue(config)} onChange={event => selectType(event.target.value)}>
        <option value="" disabled>Select a component…</option>
        {componentTypeChoices(metadata.data?.components || [], type === "edit" ? config : undefined, category).map(choice => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
      </select></label>
      {metadata.error && <Notice error>{metadata.error}</Notice>}
      <ValidationMessage context={context} path={getConfigPath("config.generic.scheme")} />
      <General context={context} type={type} component />
    </FormSection>
    {kind === "GENERIC" && <CamelGenericComponent key={`${getConfigPath("config")}:${config.generic?.scheme || ""}`} context={context} />}
    {(kind === "HTTP" || kind === "HTTPS") && <FormSection title="Connection">
      <ValidationMessage context={context} path={getConfigPath("config.http")} />
      {kind === "HTTPS" && <ResourceChoice context={context} field="http.tls" kind="TLS" label="TLS configuration" optional {...certificateChoice} />}
      {kind === "HTTP" && <p className="camel-hint">Host and path are set in the route.</p>}
      <Disclosure title="Timeouts" summary={`${(config.http?.connectTimeout ?? 5000) / 1000}s connect · ${(config.http?.responseTimeout ?? 30000) / 1000}s response`}
        defaultOpen={!!config.http && (Number(config.http.connectTimeout) !== 5000 || Number(config.http.responseTimeout) !== 30000)} invalid={invalid("http")}>
      <div className="camel-grid">
      <Field context={context} field="http.connectTimeout" label="Connection timeout (ms)" type="number" />
      <Field context={context} field="http.responseTimeout" label="Response timeout (ms)" type="number" />
      </div></Disclosure>
    </FormSection>}
    {kind === "MQTT5" && <FormSection title="MQTT 5 broker">
      <ValidationMessage context={context} path={getConfigPath("config.mqtt")} />
      <Field context={context} field="mqtt.brokerUrl" label="Broker URL *" placeholder="tcp://mqtt:1883" />
      <Field context={context} field="mqtt.qos" label="Quality of service" choices={[{ value: 0, label: "0 · At most once" }, { value: 1, label: "1 · At least once" }, { value: 2, label: "2 · Exactly once (MQTT)" }]} />
      <Disclosure title="Authentication & TLS" summary={config.mqtt?.username || config.mqtt?.password || config.mqtt?.tls ? "Configured" : "Optional"}
        defaultOpen={!!(config.mqtt?.username || config.mqtt?.password || config.mqtt?.tls)} invalid={invalid("mqtt.password") || invalid("mqtt.tls")}>
      <Field context={context} field="mqtt.username" label="Username" />
      <SecretField context={context} field="mqtt.password" label="Broker password" />
      <ResourceChoice context={context} field="mqtt.tls" kind="TLS" label="TLS configuration" optional {...certificateChoice} />
      </Disclosure>
      <Disclosure title="Advanced" invalid={invalid("mqtt.connectionTimeout") || invalid("mqtt.sessionExpiryInterval")}>
      <Field context={context} field="mqtt.connectionTimeout" label="Connection timeout (seconds)" type="number" />
      <Toggle context={context} field="mqtt.cleanStart" label="Clean start" />
      <Field context={context} field="mqtt.sessionExpiryInterval" label="Session expiry interval (seconds)" type="number" />
      <p className="camel-hint">Zero ends the broker session on disconnect. Broker session persistence is not Gateway store-and-forward.</p>
      </Disclosure>
    </FormSection>}
    {kind === "QUARTZ" && <FormSection title="In-memory scheduler">
      <ValidationMessage context={context} path={getConfigPath("config.quartz")} />
      <Field context={context} field="quartz.threads" label="Scheduler threads per context" type="number" />
      <p className="camel-hint">Routes recreate their schedules after restart. Missed firings during downtime are not replayed.</p>
    </FormSection>}
    <NestedResourceEditor open={creatingCertificate} itemName="certificate" parentItemName="Connection"
      Form={RegistryForm} category="certificates" defaults={resourceDefaults(registryDefaults("TLS"))}
      resourceType={REGISTRY_RESOURCE_TYPE} onCancel={() => setCreatingCertificate(false)} onCreated={name => {
        context.setValue(getConfigPath(`config.${kind === "MQTT5" ? "mqtt" : "http"}.tls`), name, { shouldDirty: true, shouldValidate: true });
        refreshCertificates();
        setCreatingCertificate(false);
      }} />
    </div>
  </StepWatcher>;
}
const registryChoicesFor = category => category === "certificates" ? [["TLS", "TLS"]]
  : category === "objects" ? [["BEAN", "Bean"]] : [["BEAN", "Bean"], ["TLS", "TLS"]];

export function RegistryForm({ context, type, category }) {
  useFormValidation(registryFormSchema);
  const { getConfigPath } = useConfigurationModeContext();
  const kind = context.watch(getConfigPath("config.type")) || "BEAN";
  const config = context.watch(getConfigPath("config")) || {};
  const invalid = field => !!fieldError(context, getConfigPath(`config.${field}`));
  const choices = type === "edit" && kind === "DATABASE" ? [["DATABASE", "Existing database alias"]] : registryChoicesFor(category);
  return <StepWatcher validateOnRender={type === "edit"}>
    <div className="camel-ui camel-form">
    <FormSection title={category === "certificates" ? "Certificate set" : "Advanced object"}>
      {choices.length > 1 ? <TypeSelector context={context} label="Object type" defaults={registryDefaults} choices={choices} />
        : <p className="camel-hint camel-type-fixed">{kind === "TLS" ? "TLS trust and client certificates, read from Secret Provider references."
          : kind === "DATABASE" ? "Edit an existing database alias. New connections reference Ignition databases directly." : "Create an integration-owned bean using a project function."}</p>}
      <General context={context} type={type} />
    </FormSection>
    {kind === "BEAN" && <FormSection title="Project factory" description="Create a registry object from a project-library function.">
      <ResourceChoice context={context} field="bean.project" kind="project" label="Project *" />
      <Field context={context} field="bean.functionPath" label="Factory function *" placeholder="factories.orderValidator" />
      <Disclosure title="Arguments" summary={Object.keys(config.bean?.arguments || {}).length || "None"} defaultOpen={Object.keys(config.bean?.arguments || {}).length > 0} invalid={invalid("bean.arguments")}>
      <StringMap context={context} field="bean.arguments" label="Arguments" hideLabel />
      <p className="camel-hint">Values can use {'{{property}}'} or {'{{secret.alias}}'}.</p>
      </Disclosure>
      <Disclosure title="Lifecycle" summary={config.bean?.closeOnStop === false ? "Externally owned" : "Close on stop"} defaultOpen={config.bean?.closeOnStop === false}>
        <Toggle context={context} field="bean.closeOnStop" label="Close object when the context stops" />
      </Disclosure>
    </FormSection>}
    {kind === "TLS" && <FormSection title="Certificates" description="Select Secret Provider references for keystore contents and passwords.">
      <ValidationMessage context={context} path={getConfigPath("config.tls")} />
      <Disclosure title="Trusted certificates" summary={config.tls?.trustStore?.secretName || "System trust"} defaultOpen={!!config.tls?.trustStore}
        invalid={invalid("tls.trustStore") || invalid("tls.trustStorePassword")}>
      <SecretField context={context} field="tls.trustStore" label="Truststore contents" />
      <SecretField context={context} field="tls.trustStorePassword" label="Truststore password" />
      <p className="camel-hint">Store contents are read from a base64-encoded secret.</p>
      </Disclosure>
      <Disclosure title="Client certificate" summary={config.tls?.keyStore?.secretName || "None"} defaultOpen={!!config.tls?.keyStore}
        invalid={invalid("tls.keyStore") || invalid("tls.keyStorePassword") || invalid("tls.privateKeyPassword")}>
      <SecretField context={context} field="tls.keyStore" label="Client keystore contents" />
      <SecretField context={context} field="tls.keyStorePassword" label="Client keystore password" />
      <Disclosure title="Separate private-key password" defaultOpen={!!config.tls?.privateKeyPassword}>
        <SecretField context={context} field="tls.privateKeyPassword" label="Private-key password" />
      </Disclosure>
      </Disclosure>
      <Disclosure title="Advanced" summary={`${config.tls?.protocol || "TLS"} · ${config.tls?.storeType || "PKCS12"}`} invalid={invalid("tls.protocol") || invalid("tls.storeType")}>
      <div className="camel-grid">
        <Field context={context} field="tls.protocol" label="TLS protocol" choices={["TLS", "TLSv1.2", "TLSv1.3"].map(value => ({ value, label: value }))} />
        <Field context={context} field="tls.storeType" label="Store format" choices={["PKCS12", "JKS"].map(value => ({ value, label: value }))} />
      </div>
      </Disclosure>
    </FormSection>}
    {kind === "DATABASE" && <FormSection title="Connection">
      <ResourceChoice context={context} field="database" kind="database" label="Native Ignition database connection *" />
    </FormSection>}
    </div>
  </StepWatcher>;
}

const labels = { MQTT5: "MQTT 5", QUARTZ: "Quartz", BEAN: "Bean", DATABASE: "Database", TLS: "TLS", HTTP: "HTTP", HTTPS: "HTTPS" };
const typeCell = ({ row }) => <Tag>{row.originalValue.config?.generic?.scheme || labels[row.originalValue.config?.type] || row.originalValue.config?.type}</Tag>;
export const componentColumns = [{ header: "NAME", fieldName: "name" }, { header: "TYPE", fieldName: "config.type", cell: typeCell },
  { header: "CONNECTION", fieldName: "connection", sortable: false, cell: ({ row }) => {
    const config = row.originalValue.config || {};
    return <span className="camel-cell-secondary">{config.type === "MQTT5" ? config.mqtt?.brokerUrl : config.type === "QUARTZ" ? "In-memory" : config.type === "HTTPS" ? config.http?.tls || "System trust" : "—"}</span>;
  } }, { header: "ENABLED", fieldName: "enabled", cell: enabledCell }];
export const schedulerColumns = [{ header: "NAME", fieldName: "name" },
  { header: "TYPE", fieldName: "config.type", cell: typeCell },
  { header: "THREADS", fieldName: "config.quartz.threads", sortable: false, cell: ({ row }) => <span className="camel-cell-secondary">{row.originalValue.config?.quartz?.threads ?? "\u2014"}</span> },
  { header: "ENABLED", fieldName: "enabled", cell: enabledCell }];
export const componentDefaultsColumns = [{ header: "NAME", fieldName: "name" },
  { header: "TYPE", fieldName: "config.type", cell: typeCell },
  { header: "ENABLED", fieldName: "enabled", cell: enabledCell }];
export const certificateColumns = [{ header: "NAME", fieldName: "name" },
  { header: "TRUST", fieldName: "trust", sortable: false, cell: ({ row }) => <span className="camel-cell-secondary">{row.originalValue.config?.tls?.trustStore?.secretName || "System trust"}</span> },
  { header: "CLIENT CERTIFICATE", fieldName: "client", sortable: false, cell: ({ row }) => <span className="camel-cell-secondary">{row.originalValue.config?.tls?.keyStore?.secretName || "None"}</span> },
  { header: "ENABLED", fieldName: "enabled", cell: enabledCell }];
export const registryColumns = [{ header: "NAME", fieldName: "name" }, { header: "TYPE", fieldName: "config.type", cell: typeCell },
  { header: "CONFIGURATION", fieldName: "configuration", sortable: false, cell: ({ row }) => {
    const config = row.originalValue.config || {};
    return <span className="camel-cell-secondary">{config.type === "BEAN" ? `${config.bean?.project} · ${config.bean?.functionPath}` : config.type === "DATABASE" ? config.database : config.tls?.trustStore ? "Custom trust" : "System trust"}</span>;
  } }, { header: "ENABLED", fieldName: "enabled", cell: enabledCell }];
