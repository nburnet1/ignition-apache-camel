import React from "react";
import { BaseFormControlInput, SelectInput, TextArea, TextInput, yup } from "@inductiveautomation/ignition-web-ui";
import { StepWatcher, useConfigurationModeContext, useFormValidation } from "@inductiveautomation/ignition-gateway-lib";
import { enabledCell } from "../resourceCells";
import { SelectionList, useOptions } from "./configurationFields";
import { FormSection, Notice, Tabs, fieldError } from "./CamelUi";

const configSchema = yup.object({
  language: yup.string().oneOf(["YAML", "JYTHON"]).required(),
  yaml: yup.string().nullable().when("language", { is: "YAML", then: schema => schema.max(262144).required("Kamelet YAML is required.") }),
  project: yup.string().nullable().when("language", { is: "JYTHON", then: schema => schema.trim().required("Project is required.") }),
  functionPath: yup.string().nullable().when("language", { is: "JYTHON", then: schema => schema.trim().required("Function path is required.")
    .matches(/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/, "Use a project-library function path.") }),
  kamelets: yup.array().of(yup.string().required()).max(32).default([]),
});
const schema = yup.object({
  name: yup.string().trim().required("Name is required.").matches(/^[a-z][a-z0-9-]{0,63}$/, "Use 1–64 lowercase letters/digits/hyphens, starting with a letter.")
    .notOneOf(["source", "sink"], "source and sink are reserved."),
  description: yup.string().nullable(), config: configSchema.required(), backupConfig: configSchema.nullable().default(null),
});

export function KameletForm({ context, type }) {
  useFormValidation(schema);
  const { getConfigPath, prefix } = useConfigurationModeContext();
  const language = context.watch(getConfigPath("config.language")) || "YAML";
  const options = useOptions("/data/apache-camel/camel-kamelets");
  const [tab, setTab] = React.useState("definition");
  const panelIdPrefix = React.useId();
  const [fileError, setFileError] = React.useState("");
  const name = context.watch("name");
  const errors = fieldError(context, getConfigPath("config"));
  const submitCount = context.formState?.submitCount;
  React.useEffect(() => {
    if (!submitCount || !errors) return;
    setTab(errors.kamelets ? "dependencies" : "definition");
  }, [errors, submitCount]);
  const importYaml = async event => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (file.size > 262144) { setFileError("Choose a YAML file up to 256 KiB."); return; }
    try {
      const value = await file.text();
      context.setValue(getConfigPath("config.yaml"), value, { shouldDirty: true, shouldValidate: true });
      setFileError("");
    } catch { setFileError("Could not read the selected file."); }
  };
  return <StepWatcher validateOnRender={type === "edit"}>
    <div className="camel-ui camel-form">
      <FormSection title="Template">
        {type !== "edit" && <BaseFormControlInput context={context} id={`${prefix}-kamelet-name`} name="name" label="Name *" input={<TextInput placeholder="normalize-temperature-action" />} />}
        <BaseFormControlInput context={context} id={`${prefix}-kamelet-description`} name="description" label="Description" input={<TextInput />} />
      </FormSection>
      <Tabs label="Kamelet settings" panelIdPrefix={panelIdPrefix} value={tab} onChange={setTab} tabs={[
        { id: "definition", label: "Definition", invalid: !!(errors?.yaml || errors?.project || errors?.functionPath || errors?.language) },
        { id: "dependencies", label: "Resources", invalid: !!errors?.kamelets },
      ]} />
      <div role="tabpanel" aria-label="Definition" id={`${panelIdPrefix}-panel-definition`} aria-labelledby={`${panelIdPrefix}-tab-definition`} tabIndex={0} hidden={tab !== "definition"}>
        <FormSection title="Source">
          <BaseFormControlInput context={context} id={`${prefix}-kamelet-language`} name={getConfigPath("config.language")} label="Language *"
            input={<SelectInput values={[{ value: "YAML", label: "YAML" }, { value: "JYTHON", label: "Jython DSL" }]} />} />
          {language === "JYTHON" ? <>
            <BaseFormControlInput context={context} id={`${prefix}-kamelet-project`} name={getConfigPath("config.project")} label="Project *" input={<TextInput placeholder="MyProject" />} />
            <BaseFormControlInput context={context} id={`${prefix}-kamelet-function`} name={getConfigPath("config.functionPath")} label="Function path *" input={<TextInput placeholder="camel_kamelets.greeting" />} />
            <p className="camel-hint">Function accepts one builder. Use builder.template() for parameters and builder.from_() for the template input.</p>
          </> : <>
            <div className="camel-file-field"><label className="camel-control-label" htmlFor={`${prefix}-kamelet-file`}>Import YAML file <span className="camel-muted">(optional)</span></label>
            <input id={`${prefix}-kamelet-file`} type="file" accept=".yaml,.yml" onChange={importYaml} /></div>
            {fileError && <Notice error>{fileError}</Notice>}
            <BaseFormControlInput context={context} id={`${prefix}-kamelet-yaml`} name={getConfigPath("config.yaml")} label="Kamelet YAML *"
              input={<TextArea aria-label="Kamelet YAML *" initialHeight={400} spellCheck={false} style={{ fontFamily: "monospace", tabSize: 4, whiteSpace: "pre" }} />} />
            <p className="camel-hint">Saved as config.kamelet.yaml. The resource name is the template ID.</p>
          </>}
        </FormSection>
      </div>
      <div role="tabpanel" aria-label="Resources" id={`${panelIdPrefix}-panel-dependencies`} aria-labelledby={`${panelIdPrefix}-tab-dependencies`} tabIndex={0} hidden={tab !== "dependencies"}>
        <FormSection title="Nested templates">
          {options.error && <Notice error>{options.error}</Notice>}
          <SelectionList context={context} field="kamelets" label="Kamelet definitions" choices={(options.data?.definitions || []).filter(value => value.name !== name)} />
          {errors?.kamelets && <p className="camel-error" role="alert">Select up to 32 distinct Kamelet definitions.</p>}
          <p className="camel-hint">Only needed when this template calls other Kamelets. Connections, certificates, objects and secrets are supplied by the integration that uses it.</p>
        </FormSection>
      </div>
    </div>
  </StepWatcher>;
}

export const kameletColumns = [
  { header: "NAME", fieldName: "name" },
  { header: "LANGUAGE", fieldName: "config.language", cell: ({ row }) => row.originalValue.config?.language || "YAML" },
  { header: "ENABLED", fieldName: "enabled", cell: enabledCell },
];
