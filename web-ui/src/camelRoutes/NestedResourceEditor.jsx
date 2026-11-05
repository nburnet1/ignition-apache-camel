import React from "react";
import { FormProvider, useForm } from "react-hook-form";
import { yupResolver } from "@hookform/resolvers/yup";
import { StepWorkflowContext, useStepWorkflow } from "@inductiveautomation/ignition-web-ui";
import {
  ConfigurationModeProvider, NestedStepWorkflow, SchemaValidationProvider, useCreateResourceMutation,
} from "@inductiveautomation/ignition-gateway-lib";
import { Notice } from "./CamelUi";

const NESTED_STEPS = [{ id: "nested-resource", validationRequired: true }];

export function describeCreateFailure(result) {
  const error = result?.error;
  if (error) {
    const data = error.data || {};
    if (Array.isArray(data.messages) && data.messages.length) return data.messages.join(" ");
    if (Array.isArray(data.fieldMessages) && data.fieldMessages.length) return data.fieldMessages.map(item => `${item.fieldName}: ${item.messages.join(" ")}`).join(" ");
    return data.problem?.message || data.message || data.error || (typeof error.error === "string" ? error.error : "") || `HTTP ${error.status || "error"}`;
  }
  const body = result?.data;
  if (body && body.success === false) return body.problem?.message || "The Gateway rejected the resource.";
  return "";
}

export function createResourceRequest(resourceType, values) {
  const request = { resourceType, name: values.name, description: values.description || "", enabled: values.enabled !== false, config: values.config };
  if (values.backupConfig) request.backupConfig = values.backupConfig;
  return request;
}

function NestedForm({ Form, category, defaults, resourceType, onCreated, onFailure, submitRef }) {
  const schemaRef = React.useRef();
  const [schemaVersion, setSchemaVersion] = React.useState(0);
  const setCurrentSchema = React.useCallback(next => {
    schemaRef.current = typeof next === "function" ? next(schemaRef.current) : next;
    setSchemaVersion(value => value + 1);
  }, []);
  const form = useForm({ mode: "onChange", defaultValues: defaults, resolver: (values, context, options) => schemaRef.current
    ? yupResolver(schemaRef.current)(values, context, options) : { values, errors: {} } });
  const workflow = useStepWorkflow({ steps: NESTED_STEPS });
  const [createResource] = useCreateResourceMutation();
  React.useEffect(() => { if (schemaVersion) form.trigger(); }, [schemaVersion]);
  submitRef.current = form.handleSubmit(async values => {
    const result = await createResource(createResourceRequest(resourceType, values));
    const failure = describeCreateFailure(result);
    if (failure) {
      for (const item of result.error?.data?.fieldMessages || []) form.setError(item.fieldName, { type: "server", message: item.messages.join(" ") });
      onFailure(failure);
      return;
    }
    onCreated(values.name);
  });
  const stepContext = React.useMemo(() => ({ ...workflow, steps: NESTED_STEPS }), [workflow]);
  return <ConfigurationModeProvider mode="primary" modes={["primary"]}>
    <SchemaValidationProvider setCurrentSchema={setCurrentSchema}>
      <StepWorkflowContext.Provider value={stepContext}>
        <FormProvider {...form}>
          <NestedFormValidity form={form} />
          <Form context={form} type="create" category={category} />
        </FormProvider>
      </StepWorkflowContext.Provider>
    </SchemaValidationProvider>
  </ConfigurationModeProvider>;
}

const ValidityContext = React.createContext(() => {});
function NestedFormValidity({ form }) {
  const report = React.useContext(ValidityContext);
  const { isValid, isSubmitting } = form.formState;
  React.useEffect(() => { report(isValid && !isSubmitting); }, [isValid, isSubmitting, report]);
  return null;
}

export default function NestedResourceEditor({ open, itemName, parentItemName = "Integration", Form, category, defaults, resourceType, onCreated, onCancel }) {
  const [valid, setValid] = React.useState(false);
  const [error, setError] = React.useState("");
  const submitRef = React.useRef(() => {});
  React.useEffect(() => { if (!open) { setError(""); setValid(false); } }, [open]);
  if (!open) return null;
  const created = name => { setError(""); onCreated(name); };
  return <NestedStepWorkflow open={open} type="create" itemName={itemName} parentItemName={parentItemName}
    primaryActionText={`Create ${itemName}`} isValid={valid} onFinish={() => submitRef.current()} onCancel={onCancel}>
    {open && <div className="camel-ui camel-nested-form">
      <p className="camel-hint">Created in the Library and selected for this {parentItemName.toLowerCase()}. Your draft stays open.</p>
      {error && <Notice error>{error}</Notice>}
      <ValidityContext.Provider value={setValid}>
        <NestedForm key={`${resourceType}:${category}`} Form={Form} category={category} defaults={defaults} resourceType={resourceType}
          onCreated={created} onFailure={setError} submitRef={submitRef} />
      </ValidityContext.Provider>
    </div>}
  </NestedStepWorkflow>;
}
