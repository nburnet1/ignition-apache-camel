import { componentCategory } from "./componentTypes.js";

// Deep-link contract with the Gateway hook: /services/apache-camel/library?tab=<id>. Unknown or
// missing ids fall back to the first category so stale links still land on a working page.
export const MODULE_ID = "com.noahburnette.ignition.apachecamel";
export const LIBRARY_PATH = "/services/apache-camel/library";
export const INTEGRATIONS_PATH = "/services/apache-camel/integrations";

export const LIBRARY_TABS = [
  { id: "connections", label: "Connections", resourceType: `${MODULE_ID}/camel-component`, itemName: "Connection",
    description: "Profiles for components marked remote by Camel, including network services and files." },
  { id: "certificates", label: "Certificates", resourceType: `${MODULE_ID}/camel-registry`, itemName: "Certificate",
    description: "TLS trust and client certificate sets, referenced by HTTPS and MQTT connections." },
  { id: "scheduling", label: "Scheduling", resourceType: `${MODULE_ID}/camel-component`, itemName: "Scheduler",
    description: "Defaults for components labelled scheduling by Camel, including Quartz, Timer and Cron." },
  { id: "objects", label: "Advanced objects", resourceType: `${MODULE_ID}/camel-registry`, itemName: "Object",
    description: "Named beans created by project functions and existing registry aliases." },
  { id: "templates", label: "Templates", resourceType: `${MODULE_ID}/camel-kamelet`, itemName: "Template",
    description: "Kamelets: reusable route templates selected by integrations." },
  { id: "component-defaults", label: "Advanced component defaults", resourceType: `${MODULE_ID}/camel-component`, itemName: "Component",
    description: "Defaults for local components and components without category metadata. Bean component defaults configure invocation; create bean objects under Advanced objects." },
];

export const DEFAULT_LIBRARY_TAB = LIBRARY_TABS[0].id;

export const isLibraryTab = id => LIBRARY_TABS.some(tab => tab.id === id);

export const libraryTab = id => LIBRARY_TABS.find(tab => tab.id === id);

export function libraryTabFromSearch(search, fallback = DEFAULT_LIBRARY_TAB) {
  const value = new URLSearchParams(search || "").get("tab");
  return isLibraryTab(value) ? value : fallback;
}

export function libraryHref(tab, { absolute = false } = {}) {
  const id = isLibraryTab(tab) ? tab : DEFAULT_LIBRARY_TAB;
  return `${absolute ? "/app" : ""}${LIBRARY_PATH}?tab=${id}`;
}

export function searchWithTab(search, tab) {
  const params = new URLSearchParams(search || "");
  params.set("tab", isLibraryTab(tab) ? tab : DEFAULT_LIBRARY_TAB);
  return `?${params.toString()}`;
}

export function categoryOf(resourceType, config, schemas = []) {
  const type = config?.type;
  if (resourceType.endsWith("/camel-component")) return componentCategory(config, schemas);
  if (resourceType.endsWith("/camel-registry")) return type === "TLS" ? "certificates" : "objects";
  if (resourceType.endsWith("/camel-kamelet")) return "templates";
  return undefined;
}

export const categoryFilter = (tab, schemas) => items => (items || []).filter(item => categoryOf(libraryTab(tab)?.resourceType || "", item.config, schemas) === tab);

export function groupResourceOptions(options, kamelets, schemas) {
  const components = options?.components || [];
  const registry = options?.registry || [];
  return {
    connections: components.filter(item => componentCategory(item, schemas) === "connections"),
    scheduling: components.filter(item => componentCategory(item, schemas) === "scheduling"),
    "component-defaults": components.filter(item => componentCategory(item, schemas) === "component-defaults"),
    certificates: registry.filter(item => item.type === "TLS"),
    objects: registry.filter(item => item.type !== "TLS"),
    templates: kamelets?.definitions || [],
  };
}

// The Gateway binds a selected connection's registryReferences (for example its TLS set) at startup
// whether or not the integration lists them, so the editor shows them as included instead of asking
// for a second selection.
export function includedReferences(selectedComponents, components) {
  const byName = new Map((components || []).map(item => [item.name, item]));
  const included = new Map();
  for (const name of selectedComponents || []) {
    for (const reference of byName.get(name)?.registryReferences || []) {
      if (reference.startsWith("ignition.database/")) continue;
      if (!included.has(reference)) included.set(reference, []);
      included.get(reference).push(name);
    }
  }
  return [...included.entries()].map(([name, via]) => ({ name, via }));
}

export function classifySelections(config, groups) {
  const result = [];
  for (const tab of LIBRARY_TABS) {
    const field = tab.resourceType.endsWith("/camel-component") ? "components" : tab.resourceType.endsWith("/camel-registry") ? "registry" : "kamelets";
    const names = (config?.[field] || []).filter(name => (groups[tab.id] || []).some(item => item.name === name));
    if (names.length) result.push({ id: tab.id, label: tab.label, items: names.map(name => groups[tab.id].find(item => item.name === name)) });
  }
  return result;
}

export function unavailableSelections(config, groups) {
  const known = {
    components: new Set([...groups.connections, ...groups.scheduling, ...groups["component-defaults"]].map(item => item.name)),
    registry: new Set([...groups.certificates, ...groups.objects].map(item => item.name)),
    kamelets: new Set(groups.templates.map(item => item.name)),
  };
  const missing = [];
  for (const field of ["components", "registry", "kamelets"]) {
    for (const name of config?.[field] || []) {
      if (!known[field].has(name)) missing.push({ field, name });
    }
  }
  return missing;
}
