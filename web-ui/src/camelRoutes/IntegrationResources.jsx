import React from "react";
import { useConfigurationModeContext } from "@inductiveautomation/ignition-gateway-lib";
import { SelectionList, useOptions } from "./configurationFields";
import { FormSection, Notice, RemoveButton, Tag } from "./CamelUi";
import LibraryLink from "./LibraryLink";
import NestedResourceEditor from "./NestedResourceEditor";
import {
  COMPONENT_RESOURCE_TYPE, ComponentForm, REGISTRY_RESOURCE_TYPE, RegistryForm, componentDefaults, registryDefaults, resourceDefaults,
} from "./CamelConfigurationPages";
import { KameletForm } from "./CamelKameletsPage";
import { KAMELET_RESOURCE_TYPE, kameletDefaults } from "./kameletDefaults";
import { groupResourceOptions, includedReferences, libraryTab, unavailableSelections } from "./libraryTabs";

export const RESOURCE_GROUPS = [
  { id: "connections", field: "components", Form: ComponentForm, resourceType: COMPONENT_RESOURCE_TYPE, defaults: () => resourceDefaults(componentDefaults("HTTPS")) },
  { id: "certificates", field: "registry", Form: RegistryForm, resourceType: REGISTRY_RESOURCE_TYPE, defaults: () => resourceDefaults(registryDefaults("TLS")) },
  { id: "scheduling", field: "components", Form: ComponentForm, resourceType: COMPONENT_RESOURCE_TYPE, defaults: () => resourceDefaults(componentDefaults("QUARTZ")) },
  { id: "objects", field: "registry", Form: RegistryForm, resourceType: REGISTRY_RESOURCE_TYPE, defaults: () => resourceDefaults(registryDefaults("BEAN")) },
  { id: "templates", field: "kamelets", Form: KameletForm, resourceType: KAMELET_RESOURCE_TYPE, defaults: () => kameletDefaults },
  { id: "component-defaults", field: "components", Form: ComponentForm, resourceType: COMPONENT_RESOURCE_TYPE, defaults: () => resourceDefaults(componentDefaults("GENERIC")) },
];

export const selectedResourceCount = config => (config?.components?.length || 0) + (config?.registry?.length || 0) + (config?.kamelets?.length || 0);

const limitMessage = { components: "Select up to 32 distinct connections, schedulers and component defaults.", registry: "Select up to 32 distinct certificates and objects.", kamelets: "Select up to 32 distinct templates." };

function ResourceGroup({ group, context, choices, included, error, onCreate }) {
  const tab = libraryTab(group.id);
  return <FormSection title={tab.label} description={tab.description}
    trailing={<div className="camel-group-actions"><button type="button" className="camel-button" onClick={onCreate}>Create {tab.itemName.toLowerCase()}</button><LibraryLink tab={tab.id} newTab /></div>}>
    <SelectionList context={context} field={group.field} label={tab.label} hideLabel orphans={false} choices={choices}
      emptyText={`No ${tab.label.toLowerCase()} in the Library yet. Create one here or open the Library.`} />
    {included?.length > 0 && <ul className="camel-included" aria-label={`${tab.label} included by selected connections`}>
      {included.map(item => <li key={item.name}><span>{item.name}</span><Tag>Included via {item.via.join(", ")}</Tag></li>)}
    </ul>}
    {error && <p className="camel-error" role="alert">{limitMessage[group.field]}</p>}
  </FormSection>;
}

export default function IntegrationResources({ context, options, kamelets, errors }) {
  const { getConfigPath } = useConfigurationModeContext();
  const config = context.watch(getConfigPath("config")) || {};
  const metadata = useOptions("/data/apache-camel/camel-component-metadata");
  const groups = groupResourceOptions(options.data, kamelets.data, metadata.data?.components);
  const included = includedReferences(config.components, options.data?.components);
  const missing = options.data && kamelets.data ? unavailableSelections(config, groups) : [];
  const [creating, setCreating] = React.useState(null);
  const active = RESOURCE_GROUPS.find(group => group.id === creating) || RESOURCE_GROUPS[0];
  const write = (field, next) => context.setValue(getConfigPath(`config.${field}`), next, { shouldDirty: true, shouldValidate: true });
  const created = name => {
    const current = config[active.field] || [];
    if (!current.includes(name)) write(active.field, [...current, name]);
    (active.field === "kamelets" ? kamelets : options).refresh();
    setCreating(null);
  };
  return <>
    <p className="camel-hint camel-resources-intro">Attach Library definitions once for all routes in this integration. Each integration creates its own runtime instances. Supporting references are included automatically.</p>
    {options.error && <Notice error>{options.error}</Notice>}
    {kamelets.error && <Notice error>{kamelets.error}</Notice>}
    {metadata.error && <Notice error>{metadata.error}</Notice>}
    {!metadata.data && !metadata.error && <p className="camel-hint">Loading component categories...</p>}
    {RESOURCE_GROUPS.filter(group => group.field !== "components" || metadata.data).map(group => <ResourceGroup key={group.id} group={group} context={context} choices={groups[group.id]}
      included={included.filter(item => groups[group.id].some(resource => resource.name === item.name && group.field === "registry"))}
      error={errors?.[group.field]} onCreate={() => setCreating(group.id)} />)}
    {included.some(item => !options.data?.registry?.some(resource => resource.name === item.name)) && <Notice error>
      A supporting object is unavailable: {included.filter(item => !options.data?.registry?.some(resource => resource.name === item.name)).map(item => item.name).join(", ")}. Check its definition in Library.
    </Notice>}
    {missing.length > 0 && <FormSection title="Unavailable selections" description="These names are selected but no longer exist in the Library. Remove them or recreate the resource.">
      <ul className="camel-included camel-included--missing">{missing.map(item => <li key={`${item.field}:${item.name}`}><span>{item.name}</span><Tag tone="error">{item.field}</Tag>
        <RemoveButton label={`Remove ${item.name}`} onClick={() => write(item.field, (config[item.field] || []).filter(name => name !== item.name))} /></li>)}</ul>
    </FormSection>}
    <NestedResourceEditor open={!!creating} itemName={libraryTab(active.id).itemName.toLowerCase()} Form={active.Form} category={active.id}
      defaults={active.defaults()} resourceType={active.resourceType} onCreated={created} onCancel={() => setCreating(null)} />
  </>;
}
