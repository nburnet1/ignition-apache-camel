---
title: Native Database Connections
---
# Native Database Connections

Configure a database once in Ignition's **Connections → Databases → Connections** page.
Camel references that connection by its exact name; no Camel database resource,
alias, credentials, driver configuration, or separate pool is required.

The Camel registry name is `ignition.database/<connection name>`. For an Ignition
connection named `ERP`, use `ignition.database/ERP`. The slash keeps these references
distinct from saved Camel object names, including existing dotted database aliases.

## YAML

With the compatible Camel JDBC component installed through **Extensions**:

```yaml
- route:
    id: database-check
    from:
      uri: direct:database-check
      steps:
        - setBody:
            constant: "select 1 as healthy"
        - to: jdbc:ignition.database/ERP
```

For the Camel SQL component, reference the same native connection through its
DataSource option:

```yaml
- to:
    uri: "sql:select 1 as healthy"
    parameters:
      dataSource: "#bean:ignition.database/ERP"
```

`#bean:` is Camel's standard syntax for looking up a named object in its registry.
The SQL component's `dataSource` option expects a `javax.sql.DataSource` object,
so the prefix tells Camel to resolve `ignition.database/ERP` to that object.
The module supplies access to Ignition's existing connection automatically;
you do not create a separate bean, alias, or pool.

The JDBC URI omits `#bean:` because `jdbc:ignition.database/ERP` already expects
a registry name in that position. Keep the quotes around the SQL option's value
so YAML treats the leading `#` as part of the reference.

JDBC and SQL are optional Camel components; installing them does not require
copying Ignition's database driver into Camel. Ignition owns the driver and pool.

## Component settings

When a component's object option requires a `javax.sql.DataSource`, its selector
lists native Ignition database connections directly. Select `ERP`; the saved
reference is `ignition.database/ERP`. Do not create a Library object or attach
another database resource to the integration.

## Jython

Use the same reference in a builder:

```python
(builder.from_("direct:database-check")
    .setBody().constant("select 1 as healthy")
    .to("jdbc:ignition.database/ERP"))
```

## Ownership and existing aliases

The reference resolves when Camel looks it up. Resolution checks that the named
connection exists without opening it. Each borrow goes through Ignition's current
connection manager, so credentials, pooling, and connection availability stay
under native control. Stopping an integration does not close Ignition's pool.
No native database is selected implicitly through type-based autowiring.

Existing saved `DATABASE` aliases continue working and appear under **Advanced
objects** for editing. New configurations should use the native name directly.
Static dependency inspection never opens a database connection.
