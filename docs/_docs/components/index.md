---
title: Ignition Components
---
# Ignition Components

Use the built-in Ignition component URIs directly in a route; no Library entry
is needed.

| Component | Direction | Use |
| --- | --- | --- |
| [ignition-tag](ignition-tag/) | Consumer and producer | Subscribe to tag changes or write a tag. |
| [ignition-script](ignition-script/) | Producer | Call a Jython function. |
| [ignition-message](ignition-message/) | Producer | Send or request a Gateway message. |
| [ignition-perspective-message](ignition-perspective-message/) | Producer | Notify a Perspective session or page. |
| [ignition-gateway-http](ignition-gateway-http/) | Consumer | Expose an authenticated JSON endpoint. |

For database access, [reference an existing Ignition database connection](databases/)
directly from Camel JDBC, SQL, or a component's DataSource option.
In a DataSource option, `#bean:ignition.database/ERP` tells Camel to look up the
existing connection named `ERP`. The module resolves it automatically; no separate
bean, alias, or pool needs to be configured.

For third-party integrations, see the [Apache Camel component catalog](https://camel.apache.org/components/4.22.x/index.html) and the [integration examples](../integration-examples/).

## Reusable component defaults

In **Services → Apache Camel → Library**, component profiles are grouped using
the installed Camel component metadata:

- **Scheduling**: components with the `scheduling` label, such as Timer, Cron and Quartz.
- **Connections**: other components marked `remote: true`. Camel includes files
  and Kamelet components in this category as well as network services.
- **Advanced component defaults**: local components and components without category
  metadata, including unavailable component implementations.

The creation forms and integration **Resources** selectors use the same categories.
Changing categories does not rewrite saved definitions or integration selections.
An extension's metadata determines its category; no per-extension UI mapping is needed.

**Advanced objects** creates registry beans from project functions. The **Bean**
component under Advanced component defaults configures bean invocation; it does
not create those objects. Routes can still use installed components directly without
creating a Library profile.
