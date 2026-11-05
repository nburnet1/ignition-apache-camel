import React from "react";
import { PageHeader } from "@inductiveautomation/ignition-web-ui";
import { Content, ResourceModeDataGrid } from "@inductiveautomation/ignition-gateway-lib";
import { Notice, Tabs } from "./CamelUi";
import { useOptions } from "./configurationFields";
import { DEFAULT_LIBRARY_TAB, LIBRARY_TABS, categoryFilter, libraryTab, libraryTabFromSearch, searchWithTab } from "./libraryTabs";
import {
  ComponentForm, RegistryForm, certificateColumns, componentColumns, componentDefaults, componentDefaultsColumns, registryColumns, registryDefaults, resourceDefaults, schedulerColumns,
} from "./CamelConfigurationPages";
import { KameletForm, kameletColumns } from "./CamelKameletsPage";
import { kameletDefaults } from "./kameletDefaults";
import CamelComponents from "./CamelComponents";
import { ConfigurationDetails } from "./CamelOperationsUi";
import { DetailsIcon } from "./CamelDetails";

const ConnectionForm = props => <ComponentForm {...props} category="connections" />;
const SchedulerForm = props => <ComponentForm {...props} category="scheduling" />;
const DefaultsForm = props => <ComponentForm {...props} category="component-defaults" />;
const CertificateForm = props => <RegistryForm {...props} category="certificates" />;
const ObjectForm = props => <RegistryForm {...props} category="objects" />;

export const CATEGORY_GRIDS = {
  connections: { Form: ConnectionForm, columns: componentColumns, defaults: resourceDefaults(componentDefaults("HTTPS")), kind: "component", filtered: true, sectionHeader: "Connections",
    blank: { label: "No connections yet", content: "Create a profile for a remote component with the defaults your routes share." } },
  certificates: { Form: CertificateForm, columns: certificateColumns, defaults: resourceDefaults(registryDefaults("TLS")), kind: "registry", filtered: true, sectionHeader: "Certificates",
    blank: { label: "No certificate sets yet", content: "Reference Secret Provider entries holding trust stores and client keystores for HTTPS and MQTT connections." } },
  scheduling: { Form: SchedulerForm, columns: schedulerColumns, defaults: resourceDefaults(componentDefaults("QUARTZ")), kind: "component", filtered: true, sectionHeader: "Schedulers",
    blank: { label: "No schedulers yet", content: "Create defaults for Quartz, Timer, Cron or another installed scheduling component." } },
  objects: { Form: ObjectForm, columns: registryColumns, defaults: resourceDefaults(registryDefaults("BEAN")), kind: "registry", filtered: true, sectionHeader: "Advanced objects",
    blank: { label: "No advanced objects yet", content: "Expose project-built beans to routes by name." } },
  templates: { Form: KameletForm, columns: kameletColumns, defaults: kameletDefaults, kind: "kamelet", filtered: false, sectionHeader: "Templates",
    blank: { label: "No templates yet", content: "Define a Kamelet once and reuse it from any integration." } },
  "component-defaults": { Form: DefaultsForm, columns: componentDefaultsColumns, defaults: resourceDefaults(componentDefaults("GENERIC")), kind: "component", filtered: true, sectionHeader: "Advanced component defaults",
    blank: { label: "No component defaults yet", content: "Configure shared defaults for local components such as Bean, Direct and Log." } },
};

export function useLibraryTab(fallback = DEFAULT_LIBRARY_TAB) {
  const read = React.useCallback(() => typeof window === "undefined" ? fallback : libraryTabFromSearch(window.location.search, fallback), [fallback]);
  const [tab, setTabState] = React.useState(read);
  React.useEffect(() => {
    const sync = () => setTabState(read());
    window.addEventListener("popstate", sync);
    return () => window.removeEventListener("popstate", sync);
  }, [read]);
  const setTab = React.useCallback(next => {
    setTabState(next);
    if (typeof window === "undefined") return;
    window.history.pushState(window.history.state, "", `${window.location.pathname}${searchWithTab(window.location.search, next)}${window.location.hash}`);
  }, []);
  return [tab, setTab];
}

export default function CamelLibraryPage({ defaultTab = DEFAULT_LIBRARY_TAB }) {
  const [tab, setTab] = useLibraryTab(defaultTab);
  const [selected, setSelected] = React.useState();
  const metadata = useOptions("/data/apache-camel/camel-component-metadata");
  const showMore = React.useMemo(() => [{ text: "View Details", icon: DetailsIcon, onClick: setSelected }], []);
  const transformData = React.useMemo(() => categoryFilter(tab, metadata.data?.components), [tab, metadata.data]);
  const panelIdPrefix = React.useId();
  const grid = CATEGORY_GRIDS[tab];
  const meta = libraryTab(tab);
  return <>
    <PageHeader pageTitle="Apache Camel — Library" />
    <Content>
      <div className="camel-ui camel-page camel-library">
        <p className="camel-page-intro">Reusable configuration for integrations. Each integration owns its runtime instances; sharing a definition does not share a live object.</p>
        <Tabs label="Library categories" panelIdPrefix={panelIdPrefix} value={tab} onChange={setTab} tabs={LIBRARY_TABS.map(item => ({ id: item.id, label: item.label }))} />
        <div role="tabpanel" aria-label={meta.label} id={`${panelIdPrefix}-panel-${tab}`} aria-labelledby={`${panelIdPrefix}-tab-${tab}`} tabIndex={0}>
          <p className="camel-hint camel-library-description">{meta.description}</p>
          {metadata.error && <Notice error>{metadata.error}</Notice>}
          {grid.kind === "component" && !metadata.data && !metadata.error && <p className="camel-hint">Loading component categories...</p>}
          {(grid.kind !== "component" || metadata.data) && <ResourceModeDataGrid key={tab} id={`camel-library-${tab}-grid`} CreateForm={grid.Form} EditForm={grid.Form}
            formDefaults={grid.defaults} resourceType={meta.resourceType} itemName={meta.itemName} sectionHeader={grid.sectionHeader}
            columnDefs={grid.columns} showMore={showMore} pollData={!selected} rowSelect blankState blankStateConfig={grid.blank}
            {...(grid.filtered ? { transformData, startingQueryParam: "?limit=-1&offset=0", viewCount: -1, showViewCount: false, showPagination: false } : {})} />}
          {tab === "connections" && <CamelComponents />}
        </div>
      </div>
    </Content>
    <ConfigurationDetails kind={grid.kind} resource={selected} onClose={() => setSelected(undefined)} />
  </>;
}
