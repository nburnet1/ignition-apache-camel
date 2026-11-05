import React from "react";
import {
  BaseFormControlInput,
  PageHeader,
  SelectInput,
  StatusBadge,
  TextArea,
  TextInput,
  yup,
} from "@inductiveautomation/ignition-web-ui";
import {
  Content,
  ResourceModeDataGrid,
  StepWatcher,
  useConfigurationModeContext,
  useFormValidation,
} from "@inductiveautomation/ignition-gateway-lib";
import { enabledCell } from "../resourceCells";
import { CAMEL_RESOURCE_TYPE, camelRouteDefaults, sourceField, httpSecurityLevelChoices } from "./defaults";
import CamelDetails, { DetailsIcon } from "./CamelDetails";
import { SelectionList, StringMap, useOptions } from "./configurationFields";
import { FormSection, Notice, Tabs, fieldError } from "./CamelUi";
import IntegrationResources, { selectedResourceCount } from "./IntegrationResources";

const configSchema = yup.object({
  language: yup.string().oneOf(["YAML", "JYTHON"]).default("YAML").required(),
  yaml: yup.string().nullable().when("language", {
    is: "YAML",
    then: (schema) => schema.test("source", "YAML source is required.", (value) => !!value?.trim()),
  }),
  project: yup.string().nullable().when("language", {
    is: "JYTHON",
    then: (schema) => schema.trim().required("Project is required."),
  }),
  functionPath: yup.string().nullable().when("language", {
    is: "JYTHON",
    then: (schema) => schema.trim().required("Function path is required.")
      .matches(/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/, "Use a project-library path such as camel_routes.orders."),
  }),
  components: yup.array().of(yup.string().required()).max(32).default([]),
  registry: yup.array().of(yup.string().required()).max(32).default([]),
  kamelets: yup.array().of(yup.string().required()).max(32).default([]),
  properties: yup.object().default({}),
  secrets: yup.object().default({}),
  httpSecurityLevels: yup.array().of(yup.string().required()).max(32).default([]),
});

const formSchema = yup.object({
  name: yup.string().trim().required("Name is required.")
    .matches(/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/, "Use 1–128 letters, numbers, '.', '_' or '-' (no spaces)."),
  description: yup.string().nullable().default(""),
  config: configSchema.required(),
  backupConfig: configSchema.nullable().default(null),
});

export const EDITOR_SECTIONS = [
  { id: "definition", label: "Definition", fields: ["language", "yaml", "project", "functionPath"] },
  { id: "resources", label: "Resources", fields: ["components", "registry", "kamelets"] },
  { id: "settings", label: "Settings", fields: ["properties", "secrets", "httpSecurityLevels"] },
];

export const sectionForErrors = errors => EDITOR_SECTIONS.find(section => section.fields.some(field => errors?.[field]))?.id;

const mapSize = value => value && typeof value === "object" ? Object.keys(value).length : 0;

export const IntegrationForm = ({ context, type }) => {
  useFormValidation(formSchema);
  const { getConfigPath, prefix } = useConfigurationModeContext();
  const language = context.watch(getConfigPath("config.language")) || "YAML";
  const field = sourceField(language);
  const options = useOptions("/data/apache-camel/camel-configuration-options");
  const kamelets = useOptions("/data/apache-camel/camel-kamelets");
  const securityLevels = useOptions("/data/api/v1/resources/singleton/ignition/security-levels");
  const config = context.watch(getConfigPath("config")) || {};
  const [tab, setTab] = React.useState("definition");
  const panelIdPrefix = React.useId();
  const errors = fieldError(context, getConfigPath("config"));
  const submitCount = context.formState?.submitCount;
  React.useEffect(() => {
    if (!submitCount || !errors) return;
    const owner = sectionForErrors(errors);
    if (owner) setTab(owner);
  }, [errors, submitCount]);
  const invalid = section => EDITOR_SECTIONS.find(item => item.id === section).fields.some(name => errors?.[name]);
  const panel = id => ({ role: "tabpanel", "aria-label": EDITOR_SECTIONS.find(item => item.id === id).label, id: `${panelIdPrefix}-panel-${id}`, "aria-labelledby": `${panelIdPrefix}-tab-${id}`, tabIndex: 0, hidden: tab !== id });
  return (
    <StepWatcher validateOnRender={type === "edit"}>
      <div className="camel-ui camel-form">
      <FormSection title="Integration">
        {type !== "edit" && (
          <BaseFormControlInput context={context} id={`${prefix}-camel-name`}
            name="name" label="Name *" input={<TextInput />} />
        )}
        <BaseFormControlInput context={context} id={`${prefix}-camel-description`}
          name="description" label="Description" input={<TextInput placeholder="Optional" />} />
      </FormSection>
      <Tabs label="Integration settings" panelIdPrefix={panelIdPrefix} value={tab} onChange={setTab} tabs={[
        { id: "definition", label: "Definition", invalid: invalid("definition") },
        { id: "resources", label: "Resources", count: selectedResourceCount(config), invalid: invalid("resources") },
        { id: "settings", label: "Settings", count: mapSize(config.properties) + mapSize(config.secrets) + (config.httpSecurityLevels?.length || 0), invalid: invalid("settings") },
      ]} />
      <div {...panel("definition")} aria-labelledby={`${panelIdPrefix}-tab-definition`}>
      <FormSection title="Definition" description="One integration is one Camel context. Its definition may declare several routes; they start, stop and restart together and share this integration's resources, properties and secrets.">
        <BaseFormControlInput context={context} id={`${prefix}-camel-language`}
          name={getConfigPath("config.language")} label="Language *"
          input={<SelectInput values={[
            { value: "YAML", label: "YAML" },
            { value: "JYTHON", label: "Jython DSL" },
          ]} />} />
        {language === "JYTHON" ? <>
          <BaseFormControlInput context={context} id={`${prefix}-camel-project`}
            name={getConfigPath("config.project")} label="Project *"
            input={<TextInput placeholder="MyProject" />} />
          <BaseFormControlInput context={context} id={`${prefix}-camel-functionPath`}
            name={getConfigPath("config.functionPath")} label="Function path *"
            input={<TextInput placeholder="camel_routes.orders" />} />
          <p className="camel-hint">The function receives one builder and may add any number of routes. It is loaded from the project library when the integration starts.</p>
        </> : <>
          <BaseFormControlInput key={field} context={context} id={`${prefix}-camel-yaml`}
            name={getConfigPath("config.yaml")} label="YAML source *"
            input={<TextArea aria-label="YAML source *" initialHeight={360} spellCheck={false}
              style={{ fontFamily: "monospace", tabSize: 4, whiteSpace: "pre" }} />} />
          <p className="camel-hint">A list of <code>- route:</code> entries. Saved as config.camel.yaml.</p>
        </>}
      </FormSection>
      </div>
      <div {...panel("resources")}>
        <IntegrationResources context={context} options={options} kamelets={kamelets} errors={errors} />
      </div>
      <div {...panel("settings")}>
        <FormSection title="Properties">
          <StringMap context={context} field="properties" label="Properties" hideLabel />
          <p className="camel-hint">Use {'{{name}}'} in your routes.</p>
        </FormSection>
        <FormSection title="Secret references">
          <StringMap context={context} field="secrets" label="Secret references" secret hideLabel />
          <p className="camel-hint">Use {'{{secret.name}}'} in your routes. Only the provider and secret name are stored.</p>
        </FormSection>
        <FormSection title="HTTP access">
          <p className="camel-hint">Applies only to explicit <code>ignition-gateway-http</code> consumers. Does not expose routes.</p>
          <p className="camel-hint">The consumer path is the public path: <code>ignition-gateway-http:/v1/orders/submit</code> exposes <code>/system/integrations/v1/orders/submit</code>. No mapping is needed.</p>
          {securityLevels.error && <Notice error>{securityLevels.error}</Notice>}
          <SelectionList context={context} field="httpSecurityLevels" label="Allowed security levels"
            choices={httpSecurityLevelChoices(securityLevels.data?.config?.securityLevels)} />
          <p className="camel-hint">Any selected level permits HTTP invocation for this integration. None selected denies all callers. See View Details for registered endpoints.</p>
          {errors?.httpSecurityLevels && <p className="camel-error" role="alert">Select up to 32 distinct security levels under Authenticated.</p>}
        </FormSection>
      </div>
      </div>
    </StepWatcher>
  );
};

const RuntimeStates = React.createContext({});
const RuntimeStatusCell = ({ row }) => {
  const states = React.useContext(RuntimeStates);
  const state = states[row.originalValue.name] || "UNKNOWN";
  return <StatusBadge type={state === "RUNNING" ? "success" : state === "ERROR" ? "error" : "default"}
    customLabel={state} />;
};
export const integrationColumns = [
  { header: "NAME", fieldName: "name" },
  { header: "LANGUAGE", fieldName: "config.language",
    cell: ({ row }) => row.originalValue.config?.language || "YAML" },
  { header: "ENABLED", fieldName: "enabled", cell: enabledCell },
  { header: "RUNTIME", fieldName: "status", sortable: false,
    cell: (props) => <RuntimeStatusCell {...props} /> },
];

const CamelIntegrationsPage = () => {
  const [states, setStates] = React.useState({});
  const [error, setError] = React.useState("");
  const [selectedResource, setSelectedResource] = React.useState();
  const showMore = React.useMemo(() => [{ text: "View Details", icon: DetailsIcon, onClick: setSelectedResource }], []);
  React.useEffect(() => {
    const controller = new AbortController();
    let timer;
    const poll = async () => {
      try {
        const response = await fetch("/data/apache-camel/camel-routes", {
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const value = await response.json();
        if (!controller.signal.aborted) {
          setStates(value);
          setError("");
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          setStates({});
          setError(`Runtime status is unavailable: ${failure.message}`);
        }
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(poll, 3000);
      }
    };
    poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, []);
  return (
    <>
      <PageHeader pageTitle="Apache Camel — Integrations" />
      <Content>
        <div className="camel-ui camel-page">
        <p className="camel-page-intro">An integration is one Camel context running one or more routes. Connections, certificates, schedulers, objects and templates come from the Library.</p>
        {error && <Notice error>{error}</Notice>}
        <RuntimeStates.Provider value={states}>
          <ResourceModeDataGrid id="apache-camel-integrations-grid"
            CreateForm={IntegrationForm} EditForm={IntegrationForm}
            formDefaults={camelRouteDefaults} resourceType={CAMEL_RESOURCE_TYPE}
            itemName="Integration" sectionHeader="Integrations" columnDefs={integrationColumns} showMore={showMore} pollData={!selectedResource} rowSelect />
        </RuntimeStates.Provider>
        </div>
      </Content>
      <CamelDetails resource={selectedResource} onClose={() => setSelectedResource(undefined)} />
    </>
  );
};

export { CamelIntegrationsPage };
export default CamelIntegrationsPage;
