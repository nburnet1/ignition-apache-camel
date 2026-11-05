import assert from "node:assert/strict";
import test from "node:test";
import {
  categoryFilter, classifySelections, groupResourceOptions, includedReferences,
  libraryHref, libraryTabFromSearch, searchWithTab, unavailableSelections,
} from "../src/camelRoutes/libraryTabs.js";
import { objectReferenceChoices } from "../src/camelRoutes/componentOptions.js";
import { componentTypeChoices } from "../src/camelRoutes/componentTypes.js";

const schemas = [
  { component: { scheme: "https", title: "HTTPS", remote: true, label: "http" } },
  { component: { scheme: "paho-mqtt5", title: "MQTT", remote: true, label: "messaging,iot" } },
  { component: { scheme: "quartz", title: "Quartz", remote: false, label: "scheduling" } },
  { component: { scheme: "sql", title: "SQL", remote: true, label: "database" } },
  { component: { scheme: "clock-alias,timer", title: "Clock", remote: true, label: "core,scheduling" } },
  { component: { scheme: "bean", title: "Bean", remote: false, label: "core,script" } },
  { component: { scheme: "addon", title: "Addon" } },
];

test("Metadata partitions generic profiles without changing stored settings", () => {
  const rows = ["sql", "timer", "clock-alias", "bean", "addon", "removed"].map(scheme => ({
    name: scheme, config: { type: "GENERIC", generic: { scheme, options: { custom: "kept" } } },
  }));
  const original = structuredClone(rows);
  assert.deepEqual(categoryFilter("connections", schemas)(rows).map(row => row.name), ["sql"]);
  assert.deepEqual(categoryFilter("scheduling", schemas)(rows).map(row => row.name), ["timer", "clock-alias"]);
  assert.deepEqual(categoryFilter("component-defaults", schemas)(rows).map(row => row.name), ["bean", "addon", "removed"]);
  assert.deepEqual(rows, original);
});

test("Creation choices follow metadata while an existing unavailable type remains editable", () => {
  assert.deepEqual(componentTypeChoices(schemas, {}, "connections").map(item => item.value).sort(),
    ["HTTPS", "MQTT5", "generic:sql"]);
  assert.deepEqual(componentTypeChoices(schemas, {}, "scheduling").map(item => item.value).sort(),
    ["QUARTZ", "generic:clock-alias", "generic:timer"]);
  assert.deepEqual(componentTypeChoices(schemas, {}, "component-defaults").map(item => item.value).sort(),
    ["generic:addon", "generic:bean"]);
  const config = { type: "GENERIC", generic: { scheme: "removed" } };
  assert.ok(componentTypeChoices(schemas, config, "component-defaults").some(item => item.value === "generic:removed"));
});

test("Integration resources classify generic schemes and retain every selected profile", () => {
  const components = [
    { name: "external", type: "GENERIC", scheme: "sql" },
    { name: "pulse", type: "GENERIC", scheme: "timer" },
    { name: "invoke", type: "GENERIC", scheme: "bean" },
    { name: "old", type: "GENERIC", scheme: "removed" },
  ];
  const groups = groupResourceOptions({ components }, {}, schemas);
  const config = { components: components.map(item => item.name) };
  assert.deepEqual(classifySelections(config, groups).map(group => [group.id, group.items.map(item => item.name)]), [
    ["connections", ["external"]], ["scheduling", ["pulse"]], ["component-defaults", ["invoke", "old"]],
  ]);
  assert.deepEqual(unavailableSelections(config, groups), []);
});

const options = {
  components: [
    { name: "orders", type: "HTTPS", registryReferences: ["trust"] },
    { name: "plant", type: "MQTT5", registryReferences: ["trust", "identity"] },
    { name: "nightly", type: "QUARTZ", registryReferences: [] },
    { name: "sql", type: "GENERIC", scheme: "sql", registryReferences: ["database"] },
  ],
  registry: [
    { name: "trust", type: "TLS" }, { name: "identity", type: "TLS" },
    { name: "validator", type: "BEAN" }, { name: "database", type: "DATABASE" },
  ],
};
const templates = { definitions: [{ name: "normalize", type: "YAML" }] };

test("Library links retain other query settings and reject unknown categories", () => {
  assert.equal(libraryTabFromSearch("?tab=certificates"), "certificates");
  assert.equal(libraryTabFromSearch("?tab=unknown"), "connections");
  assert.equal(libraryTabFromSearch("", "templates"), "templates");
  assert.equal(libraryHref("objects", { absolute: true }), "/app/services/apache-camel/library?tab=objects");
  assert.equal(searchWithTab("?filter=plant&tab=objects", "connections"), "?filter=plant&tab=connections");
});

test("Native resource rows appear only in their matching Library category", () => {
  const objects = [
    { name: "trust", config: { type: "TLS" } },
    { name: "bean", config: { type: "BEAN" } },
    { name: "db", config: { type: "DATABASE" } },
  ];
  assert.deepEqual(categoryFilter("certificates")(objects).map(row => row.name), ["trust"]);
  assert.deepEqual(categoryFilter("objects")(objects).map(row => row.name), ["bean", "db"]);
  assert.deepEqual(categoryFilter("databases")(objects), []);
  assert.deepEqual(categoryFilter("connections", schemas)([
    { name: "http", config: { type: "HTTPS" } }, { name: "clock", config: { type: "QUARTZ" } },
  ]).map(row => row.name), ["http"]);
});

test("Advanced objects retain existing database aliases alongside project objects", () => {
  const groups = groupResourceOptions(options, templates, schemas);
  assert.deepEqual(groups.connections.map(row => row.name), ["orders", "plant", "sql"]);
  assert.deepEqual(groups.scheduling.map(row => row.name), ["nightly"]);
  assert.deepEqual(groups.objects.map(row => row.name), ["validator", "database"]);
  assert.deepEqual(groups.certificates.map(row => row.name), ["trust", "identity"]);
  assert.deepEqual(groups.templates.map(row => row.name), ["normalize"]);
});

test("Included references identify every selecting connection and include generic objects", () => {
  assert.deepEqual(includedReferences(["orders", "plant", "sql", "missing"], options.components), [
    { name: "trust", via: ["orders", "plant"] },
    { name: "identity", via: ["plant"] },
    { name: "database", via: ["sql"] },
  ]);
  assert.deepEqual(includedReferences([], options.components), []);
});

test("Same-named resources in another native type do not hide missing selections", () => {
  const groups = groupResourceOptions(options, templates, schemas);
  assert.deepEqual(unavailableSelections({
    components: ["validator"], registry: ["orders"], kamelets: ["database"],
  }, groups), [
    { field: "components", name: "validator" },
    { field: "registry", name: "orders" },
    { field: "kamelets", name: "database" },
  ]);
});

test("Details link existing database aliases to Advanced objects without changing selections", () => {
  const groups = groupResourceOptions(options, templates, schemas);
  const config = { components: ["orders", "nightly"], registry: ["database"], kamelets: ["normalize"] };
  const original = structuredClone(config);
  const selected = classifySelections(config, groups);
  assert.deepEqual(selected.map(group => [group.id, group.items.map(item => item.name)]), [
    ["connections", ["orders"]], ["scheduling", ["nightly"]],
    ["objects", ["database"]], ["templates", ["normalize"]],
  ]);
  assert.deepEqual(unavailableSelections(config, groups), []);
  assert.deepEqual(config, original);
});

test("Direct native database references are not missing Library objects", () => {
  const groups = groupResourceOptions(options, templates, schemas);
  const native = "ignition.database/Plant DB/Primary";
  assert.deepEqual(unavailableSelections({
    registry: ["database", "missing"], components: [native], kamelets: [],
  }, groups), [{ field: "components", name: native }, { field: "registry", name: "missing" }]);
  assert.deepEqual(includedReferences(["sql"], [
    { name: "sql", registryReferences: [native, "database"] },
  ]), [{ name: "database", via: ["sql"] }]);
});

const dataSource = { type: "object", javaType: "javax.sql.DataSource" };

test("Native database choices cannot collide with valid dotted alias names", () => {
  const alias = "ignition.database.Reporting";
  const registry = [{ name: alias, type: "DATABASE", enabled: true }];
  assert.deepEqual(objectReferenceChoices(dataSource, {
    databases: [{ name: "Reporting" }], registry,
  }, alias), [
    { value: "ignition.database/Reporting", label: "Reporting (native Ignition database)" },
    { value: alias, label: `${alias} (existing alias)` },
  ]);
  const groups = groupResourceOptions({ components: [], registry }, { definitions: [] });
  assert.deepEqual(unavailableSelections({ registry: [alias] }, groups), []);
  assert.deepEqual(includedReferences(["jdbc"], [{ name: "jdbc", registryReferences: [alias] }]),
    [{ name: alias, via: ["jdbc"] }]);
});

test("DataSource choices save exact native names with the lazy registry namespace", () => {
  const resources = {
    databases: [{ name: " Plant DB/Primary.v2 ", enabled: true }, { name: "offline", enabled: false }],
    registry: [],
  };
  assert.deepEqual(objectReferenceChoices(dataSource, resources), [
    { value: "ignition.database/ Plant DB/Primary.v2 ", label: " Plant DB/Primary.v2  (native Ignition database)" },
    { value: "ignition.database/offline", label: "offline (native Ignition database) (disabled)" },
  ]);
});

test("DataSource choices keep persisted database and bean aliases selectable without rewriting", () => {
  const resources = {
    databases: [{ name: "database", enabled: true }],
    registry: [...options.registry, { name: "factoryDataSource", type: "BEAN" }],
  };
  assert.deepEqual(objectReferenceChoices(dataSource, resources, "factoryDataSource"), [
    { value: "ignition.database/database", label: "database (native Ignition database)" },
    { value: "database", label: "database (existing alias)" },
    { value: "factoryDataSource", label: "factoryDataSource (existing alias)" },
  ]);
});

test("DataSource choices preserve unavailable current values while names are absent", () => {
  for (const [current, label] of [
    ["ignition.database/Missing DB", "Missing DB (native Ignition database) (unavailable)"],
    ["oldAlias", "oldAlias (existing alias) (unavailable)"],
  ]) {
    assert.deepEqual(objectReferenceChoices(dataSource, {}, current), [{ value: current, label }]);
  }
});

test("TLS and other object choices keep registry filtering and unavailable selections", () => {
  for (const [spec, expected] of [
    [{ type: "object", javaType: "org.apache.camel.support.jsse.SSLContextParameters" }, [
      { value: "trust", label: "trust" }, { value: "identity", label: "identity" },
      { value: "gone", label: "gone (unavailable)" },
    ]],
    [{ type: "object", javaType: "com.example.Validator" }, [
      { value: "validator", label: "validator" }, { value: "gone", label: "gone (unavailable)" },
    ]],
  ]) {
    assert.deepEqual(objectReferenceChoices(spec, { registry: options.registry, databases: [{ name: "native" }] }, "gone"), expected);
  }
});
