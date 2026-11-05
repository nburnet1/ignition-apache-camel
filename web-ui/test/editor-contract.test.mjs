import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

import { CAMEL_RESOURCE_TYPE, camelRouteDefaults, sourceField, httpSecurityLevelChoices } from "../src/camelRoutes/defaults.js";
import { componentTypeChoices, componentTypeValue } from "../src/camelRoutes/componentTypes.js";
import { httpIngressSummary } from "../src/camelRoutes/httpIngress.js";
import { KAMELET_RESOURCE_TYPE, kameletDefaults } from "../src/camelRoutes/kameletDefaults.js";
import { optionSections, initialOptionValue, withoutOptionalOverrides, requiresValue } from "../src/camelRoutes/componentOptions.js";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

// Render the real shared components without a Gateway or a browser. CSS is checked
// by webpack; JSX is transformed with the same presets as the production bundle.
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { transformSync } = require("@babel/core");
const uiModule = { exports: {} };
const uiSource = readFileSync(join(here, "../src/camelRoutes/CamelUi.jsx"), "utf8");
const { code: uiCode } = transformSync(uiSource, {
  configFile: false, babelrc: false, presets: [require.resolve("@babel/preset-env"), require.resolve("@babel/preset-react")],
});
new Function("require", "module", "exports", uiCode)(name => name.endsWith(".css") ? {} : require(name), uiModule, uiModule.exports);

test("Shared sections and status tags retain clear headings and text labels", () => {
  const { FormSection, Tag } = uiModule.exports;
  const html = renderToStaticMarkup(React.createElement(FormSection, {
    title: "Certificates", description: "Select secret references.",
    trailing: React.createElement(Tag, { tone: "warning" }, "Awaiting restart"),
  }, "Settings"));
  assert.match(html, /<h3>Certificates<\/h3>/);
  assert.match(html, /Select secret references\./);
  assert.match(html, /camel-tag--warning">Awaiting restart/);
  assert.match(html, /camel-section-body">Settings/);
});

test("Shared tabs link to panels, expose validation errors and keep one tab stop", () => {
  const html = renderToStaticMarkup(React.createElement(uiModule.exports.Tabs, {
    label: "Route settings", panelIdPrefix: "route-a", value: "definition", onChange() {},
    tabs: [{ id: "definition", label: "Definition" }, { id: "access", label: "HTTP ingress", invalid: true }],
  }));
  assert.match(html, /id="route-a-tab-definition"/);
  assert.match(html, /aria-controls="route-a-panel-definition"/);
  assert.match(html, /aria-label="HTTP ingress — has errors"/);
  assert.equal((html.match(/tabindex="0"/g) || []).length, 1);
  assert.equal((html.match(/tabindex="-1"/g) || []).length, 1);
  for (const file of ["index.jsx", "CamelKameletsPage.jsx", "CamelDetails.jsx"]) {
    const source = readFileSync(join(here, "../src/camelRoutes", file), "utf8");
    assert.match(source, /panelIdPrefix=\{panelIdPrefix\}/);
    assert.match(source, /aria-labelledby=\{`\$\{panelIdPrefix\}-tab-/);
  }
});

test("Registry type drafts are isolated by configuration mode, just like component drafts", () => {
  const source = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  assert.match(source, /drafts\.current\[`\$\{root\}:\$\{config\.type\}`\] = config/);
  assert.match(source, /drafts\.current\[`\$\{root\}:\$\{value\}`\] \|\| defaults\(value\)/);
  assert.match(source, /drafts\.current\[`\$\{root\}:\$\{componentTypeValue\(config\)\}`\] = config/);
});

test("Startup errors show actionable causes instead of duplicating a generic wrapper notice", () => {
  const source = readFileSync(join(here, "../src/camelRoutes/CamelStartupError.jsx"), "utf8");
  const { code } = transformSync(source, {
    configFile: false, babelrc: false, presets: [require.resolve("@babel/preset-env"), require.resolve("@babel/preset-react")],
  });
  const module = { exports: {} };
  new Function("require", "module", "exports", code)(name => name === "./CamelUi" ? uiModule.exports : require(name), module, module.exports);
  const html = renderToStaticMarkup(React.createElement(module.exports.default, {
    failure: { code: "COMPONENT_UNAVAILABLE", title: "Endpoint component is unavailable", action: "Select it in Dependencies → Components.",
      routeId: "orders", component: "example-mqtt", exceptionTypes: ["org.apache.camel.FailedToCreateRouteException", "org.apache.camel.NoSuchEndpointException"] },
    onDependencies() {},
  }));
  assert.match(html, /role="alert"/);
  assert.match(html, /Endpoint component is unavailable/);
  assert.match(html, /example-mqtt/);
  assert.match(html, /Underlying exception/);
  assert.match(html, /Review resources/);
  assert.match(html, /Technical details/);
  const details = readFileSync(join(here, "../src/camelRoutes/CamelDetails.jsx"), "utf8");
  assert.match(details, /issue\.code !== details\.startupFailure\?\.code/);
});

test("Camel operations expose metadata-only diagnostics and dependency views", () => {
  const details = readFileSync(join(here, "../src/camelRoutes/CamelDetails.jsx"), "utf8");
  const dependency = readFileSync(join(here, "../src/camelRoutes/CamelOperationsUi.jsx"), "utf8");
  assert.match(details, /lastSucceededAt/);
  assert.match(details, /consecutiveFailures/);
  assert.match(details, /Recent error metadata/);
  assert.match(details, /No payloads, headers, exception messages/);
  assert.match(details, /DependenciesPanel kind="route"/);
  assert.match(dependency, /camel-dependencies\?kind=/);
  assert.match(dependency, /Used by \/ change impact/);
  assert.match(dependency, /AbortController/);
  assert.match(dependency, /CONFIDENCE/);
  assert.doesNotMatch(details + dependency, /Replay message|Capture sample|startTrace|startDebugger/);
});

test("Kamelets use native YAML/Jython resources, local YAML import and explicit route dependencies", () => {
  assert.equal(KAMELET_RESOURCE_TYPE, "com.noahburnette.ignition.apachecamel/camel-kamelet");
  assert.match(kameletDefaults.config.yaml, /kind: Kamelet/);
  assert.match(kameletDefaults.config.yaml, /\{\{prefix\}\}\$\{body\}/);
  assert.deepEqual(kameletDefaults.config.kamelets, []);
  assert.deepEqual(camelRouteDefaults.config.kamelets, []);
  const page = readFileSync(join(here, "../src/camelRoutes/CamelKameletsPage.jsx"), "utf8");
  assert.match(readFileSync(join(here, "../src/camelRoutes/CamelLibraryPage.jsx"), "utf8"), /ResourceModeDataGrid/);
  assert.match(page, /getConfigPath\("config\.functionPath"\)/);
  assert.match(page, /getConfigPath\("config\.yaml"\)/);
  assert.match(page, /value: "JYTHON", label: "Jython DSL"/);
  assert.match(page, /accept="\.yaml,\.yml"/);
  assert.match(page, /file\.text\(\)/);
  assert.match(page, /config\.kamelet\.yaml/);
  assert.doesNotMatch(page, /camel-extension-upload|\.jar|config\.jython/);
  const route = readFileSync(join(here, "../src/camelRoutes/IntegrationResources.jsx"), "utf8");
  assert.match(route, /field: "kamelets"/);
  const barrel = readFileSync(join(here, "../src/index.jsx"), "utf8");
  assert.match(barrel, /export \{[\s\S]*CamelKameletsPage/);
});

test("HTTP ingress describes live registrations without treating stopped or older runtimes as HTTP-free", () => {
  const endpoint = { method: "POST", path: "/system/integrations/v1/orders/submit" };
  assert.deepEqual(httpIngressSummary({ state: "RUNNING", httpEndpoints: [endpoint] }), { endpoints: [endpoint], message: "" });
  assert.equal(httpIngressSummary({ state: "RUNNING", httpEndpoints: [] }).message,
    "No HTTP endpoints registered; these permissions are inactive.");
  assert.match(httpIngressSummary({ state: "RUNNING" }).message, /unavailable from this Gateway/);
  for (const state of ["DISABLED", "ERROR", "UNKNOWN"]) {
    assert.deepEqual(httpIngressSummary({ state, httpEndpoints: [endpoint] }).endpoints, []);
    assert.match(httpIngressSummary({ state }).message, /No running runtime/);
  }
  const details = readFileSync(join(here, "../src/camelRoutes/CamelDetails.jsx"), "utf8");
  assert.match(details, /FormSection title="HTTP access"/);
  assert.match(details, /fieldName: "path"/);
});

test("HTTP access defaults to deny and selects explicit native levels per configuration mode", () => {
  assert.deepEqual(camelRouteDefaults.config.httpSecurityLevels, []);
  assert.ok(!("httpPublicPaths" in camelRouteDefaults.config));
  assert.deepEqual(httpSecurityLevelChoices(), []);
  const choices = httpSecurityLevelChoices([
    { name: "Authenticated", children: [{ name: "Integrations", children: [{ name: "OrdersSubmit" }] }] },
    { name: "SecurityZones", children: [{ name: "Default" }] },
  ]);
  assert.deepEqual(choices.map(choice => choice.name), [
    "Authenticated/Integrations", "Authenticated/Integrations/OrdersSubmit",
  ]);
  const page = readFileSync(join(here, "../src/camelRoutes/index.jsx"), "utf8");
  assert.match(page, /FormSection title="HTTP access"/);
  assert.match(page, /Does not expose routes/);
  assert.doesNotMatch(page, /field="httpPublicPaths"/);
  assert.match(page, /No mapping is needed/);
  assert.match(page, /SelectionList context=\{context\} field="httpSecurityLevels"/);
  assert.match(page, /None selected denies all callers/);
});

test("Camel resources expose native YAML and Jython DSL authoring", () => {
  assert.equal(CAMEL_RESOURCE_TYPE, "com.noahburnette.ignition.apachecamel/camel-route");
  assert.equal(camelRouteDefaults.config.language, "YAML");
  assert.match(camelRouteDefaults.config.yaml, /- route:/);
  assert.equal(camelRouteDefaults.config.project, "");
  assert.equal(camelRouteDefaults.config.functionPath, "");
  assert.ok(!("jython" in camelRouteDefaults.config), "Jython code must not live in Gateway resources");
  assert.equal(sourceField("YAML"), "yaml");
  assert.equal(sourceField("JYTHON"), "functionPath");
  const barrel = readFileSync(join(here, "../src/index.jsx"), "utf8");
  assert.match(barrel, /import CamelIntegrationsPage from "\.\/camelRoutes"/);
  assert.match(barrel, /const CamelRoutesPage = CamelIntegrationsPage/);
  assert.match(barrel, /export \{[\s\S]*CamelRoutesPage/);
  const page = readFileSync(join(here, "../src/camelRoutes/index.jsx"), "utf8");
  assert.match(page, /ResourceModeDataGrid/);
  assert.match(page, /getConfigPath\("config\.project"\)/);
  assert.match(page, /getConfigPath\("config\.functionPath"\)/);
  assert.doesNotMatch(page, /config\.jython/);
  assert.match(page, /value: "JYTHON", label: "Jython DSL"/);
});

test("Camel details follow the native View Details contract and use protected restart requests", () => {
  const page = readFileSync(join(here, "../src/camelRoutes/index.jsx"), "utf8");
  const details = readFileSync(join(here, "../src/camelRoutes/CamelDetails.jsx"), "utf8");
  assert.match(page, /text: "View Details"/);
  assert.match(page, /showMore=\{showMore\}/);
  assert.match(page, /<CamelDetails resource=\{selectedResource\}/);
  assert.match(details, /camel-route-details\?name=/);
  assert.match(details, /FormSection title="Routes"/);
  assert.match(details, /label: "Diagnostics"/);
  assert.match(details, /meanProcessingMillis/);
  assert.match(details, /X-CSRF-Token/);
  assert.match(details, /controller\.abort\(\)/);
  assert.match(details, /clearTimeout\(timer\)/);
});

test("Camel configuration uses compact forms and keeps advanced settings behind disclosures", () => {
  const pages = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  const routes = readFileSync(join(here, "../src/camelRoutes/index.jsx"), "utf8");
  const ui = readFileSync(join(here, "../src/camelRoutes/CamelUi.jsx"), "utf8");
  const fields = readFileSync(join(here, "../src/camelRoutes/configurationFields.jsx"), "utf8");
  assert.match(pages, /ChoiceGroup/);
  assert.match(pages, /Disclosure title="Authentication & TLS"/);
  assert.match(pages, /Disclosure title="Advanced"/);
  assert.match(pages, /In-memory scheduler/);
  assert.doesNotMatch(pages, /showMore.*Scheduler setup/);
  assert.match(pages, /kind === "QUARTZ"/);
  assert.match(routes, /Tabs label="Integration settings"/);
  assert.match(routes, /panel\("resources"\)/);
  assert.match(routes, /description: yup\.string\(\)\.nullable\(\)/);
  assert.match(routes, /TextArea aria-label="YAML source \*"/);
  assert.match(ui, /ArrowLeft.*ArrowRight.*Home.*End/);
  assert.match(ui, /if \(invalid\) setOpen\(true\)/);
  assert.match(fields, /RemoveButton/);
  assert.doesNotMatch(fields, /<fieldset style=/);
});

test("Camel has separate native Components and Registry resources with focused forms", () => {
  const pages = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  const fields = readFileSync(join(here, "../src/camelRoutes/configurationFields.jsx"), "utf8");
  assert.match(pages, /camel-component/);
  assert.match(pages, /camel-registry/);
  const barrel = readFileSync(join(here, "../src/index.jsx"), "utf8");
  assert.match(barrel, /CamelComponentConfigurationsPage/);
  assert.match(barrel, /CamelRegistryPage/);
  const library = readFileSync(join(here, "../src/camelRoutes/CamelLibraryPage.jsx"), "utf8");
  assert.match(library, /ResourceModeDataGrid/); // Native save actions own WRITE/CSRF enforcement.
  assert.match(library, /CreateForm=\{grid.Form\} EditForm=\{grid.Form\}/);
  assert.match(pages, /field="quartz.threads"/);
  assert.match(fields, /secret-providers/);
  assert.doesNotMatch(pages, /JSON\.parse|JsonSection/);
  assert.ok(!("runtimeProfile" in camelRouteDefaults.config));
  assert.deepEqual(camelRouteDefaults.config.components, []);
  assert.deepEqual(camelRouteDefaults.config.registry, []);
  assert.doesNotMatch(readFileSync(join(here, "../src/camelRoutes/index.jsx"), "utf8"), /runtimeProfile|legacyProfile/);
});

test("Camel extensions stage trusted JARs with explicit consent and protected mutations", () => {
  const page = readFileSync(join(here, "../src/camelRoutes/CamelExtensionsPage.jsx"), "utf8");
  const generic = readFileSync(join(here, "../src/camelRoutes/CamelGenericComponent.jsx"), "utf8");
  assert.match(page, /accept="\.jar" multiple/);
  assert.match(page, /onDrop=/);
  assert.match(page, /X-CSRF-Token/);
  assert.match(page, /camel-extension-upload\?confirm=true/);
  assert.match(page, /disabled=\{busy \|\| !trusted \|\| !files.length\}/);
  assert.match(page, /REMOVAL_PENDING/);
  const removal = readFileSync(join(here, "../src/camelRoutes/CamelExtensionRemoval.jsx"), "utf8");
  assert.match(removal, /Schedule removal/);
  assert.match(page, /Restart the Gateway/);
  assert.match(generic, /camel-component-metadata/);
  assert.match(generic, /componentProperties/);
  assert.match(generic, /spec\.secret/);
  assert.match(generic, /SecretPicker/);
  assert.match(generic, /Registry|registry/);
  assert.match(generic, /Shared component defaults/);
  assert.doesNotMatch(generic, /JSON\.parse/);
});

test("Component settings show defaults directly, preserve required fields and avoid endpoint authoring", () => {
  const schema = { componentProperties: {
    required: { type: "string", required: true, index: 0 },
    enabled: { type: "boolean", defaultValue: true, index: 1 },
    optional: { type: "string", group: "advanced", index: 2 },
  } };
  assert.deepEqual(optionSections(schema).common.map(([name]) => name), ["required", "enabled"]);
  assert.deepEqual(optionSections(schema).advanced.map(([name]) => name), ["optional"]);
  assert.equal(initialOptionValue(schema.componentProperties.enabled), "true");
  assert.equal(requiresValue({ required: true, defaultValue: false }), false);
  const reset = withoutOptionalOverrides({ scheme: "test", options: { required: "keep", enabled: "false", optional: "custom", unknown: "keep" } }, schema);
  assert.deepEqual(reset.options, { required: "keep", unknown: "keep" });
  const generic = readFileSync(join(here, "../src/camelRoutes/CamelGenericComponent.jsx"), "utf8");
  assert.match(generic, /Override default/);
  assert.match(generic, /Advanced settings/);
  assert.doesNotMatch(generic, /Add configuration options|Add option|Endpoint reference|Find an option/);
});

test("Quartz exposes only in-memory scheduling, with no database or schema controls", () => {
  const page = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  assert.match(page, /quartz: type === "QUARTZ" \? \{ threads: 2 \}/);
  assert.match(page, /In-memory scheduler/);
  assert.match(page, /Missed firings during downtime are not replayed/);
  assert.doesNotMatch(page, /initializeStorage|quartz\.database|quartz\.persistent|camel-quartz-storage|QuartzStorage/);
  assert.doesNotMatch(page, /SchedulerSetup|SetupAction|SchedulerCell|component’s Setup action/);
});

test("Extension removal requires an impact preview, fresh plan token and explicit review", () => {
  const page = readFileSync(join(here, "../src/camelRoutes/CamelExtensionsPage.jsx"), "utf8");
  const removal = readFileSync(join(here, "../src/camelRoutes/CamelExtensionRemoval.jsx"), "utf8");
  const groups = readFileSync(join(here, "../src/camelRoutes/CamelExtensionGroups.jsx"), "utf8");
  assert.match(page, /token=\$\{encodeURIComponent\(plan.token\)\}/);
  assert.match(removal, /camel-extension-removal-preview/);
  assert.match(removal, /disabled=\{!confirmed \|\| busy\}/);
  assert.match(removal, /failure.status === 409/);
  assert.match(groups, /Also used by/);
});

test("Components use one dropdown for focused and installed types without a second selector", () => {
  const pages = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  const generic = readFileSync(join(here, "../src/camelRoutes/CamelGenericComponent.jsx"), "utf8");
  assert.match(pages, /select className="camel-select" aria-label="Component type"/);
  assert.doesNotMatch(pages, /label="Component type" defaults=/);
  assert.doesNotMatch(generic, /Component implementation|selectScheme/);
  const choices = componentTypeChoices([
    { component: { scheme: "cron", title: "Cron" } },
    { component: { scheme: "http", title: "HTTP" } },
    { component: { scheme: "platform-http", title: "Platform HTTP" } },
    { component: { scheme: "ignition-gateway-http", title: "Ignition Gateway HTTP" } },
  ], { type: "HTTPS" });
  assert.equal(choices.filter(item => item.scheme === "http").length, 1);
  assert.ok(choices.some(item => item.value === "generic:cron"));
  assert.ok(!choices.some(item => item.scheme === "platform-http"));
  assert.ok(!choices.some(item => item.scheme === "ignition-gateway-http"));
  assert.equal(componentTypeValue({ type: "GENERIC", generic: { scheme: "cron" } }), "generic:cron");
  assert.ok(componentTypeChoices([], { type: "GENERIC", generic: { scheme: "missing" } }).some(item => item.label === "missing (unavailable)"));
});

test("MQTT uses only the MQTT 5 component and explicit session controls", () => {
  const choices = componentTypeChoices([{ component: { scheme: "paho-mqtt5", title: "Paho MQTT 5" } }], { type: "MQTT5" });
  assert.ok(choices.some(choice => choice.value === "MQTT5" && choice.label === "MQTT 5"));
  assert.ok(!choices.some(choice => choice.value === "MQTT" || choice.scheme === "paho"));
  const source = readFileSync(join(here, "../src/camelRoutes/CamelConfigurationPages.jsx"), "utf8");
  assert.match(source, /cleanStart: true/);
  assert.match(source, /sessionExpiryInterval: 0/);
  assert.doesNotMatch(source, /cleanSession|quartz\.identity/);
});

test("Deprecated upstream options are not offered in the component editor", () => {
  const sections = optionSections({ componentProperties: {
    supported: { type: "string" }, retired: { type: "string", deprecated: true },
    retiredRequired: { type: "string", deprecated: true, required: true },
  } });
  assert.deepEqual(sections.common.map(([name]) => name), ["supported"]);
  assert.deepEqual(sections.advanced, []);
});
