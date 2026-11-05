---
title: Gateway UI
permalink: /getting-started/index.html
---
# Gateway UI

Open **Services → Apache Camel → Integrations**. Each integration owns one
Camel context containing one or more routes. Related routes share its connections,
objects, properties, and templates.

| Page | Purpose |
| --- | --- |
| Integrations | Define related YAML or Jython routes, attach resources, and open **View Details** for individual routes and diagnostics. |
| Library | Manage reusable connection profiles, certificates, scheduling settings, advanced objects, and templates (Kamelets). |
| Extensions | Manage optional Camel JARs; changes require a Gateway restart. |

## Create and run an integration

1. Create an integration and choose YAML or Jython under **Definition**.
   Define one route or several related routes in the same source.
2. Under **Resources**, attach connections, certificates, scheduling settings,
   advanced objects, and templates. These are available to every route in this integration.
   Supporting objects referenced by a connection, such as its TLS configuration,
   are included automatically.
3. Add integration properties and secret references under **Settings**.
4. If the route exposes HTTP, select its allowed security levels under
   **Settings → HTTP access**. Leaving the selection empty denies every caller.
5. Save and enable the integration.

The **Runtime** column shows whether the integration started. If it did not, open
**View Details** for the startup error and resource diagnostics.

Saving checks YAML structure without running bean constructors, factories, or
project code. Bean creation and property resolution happen when the integration
starts; unavailable classes or runtime dependencies are reported there.

Library entries share configuration, not live component or bean instances.
Each consuming integration creates its own runtime objects. Changes to a shared
definition rebuild affected integrations; inspect **Used by / change impact**
before changing one.

Databases are configured only in Ignition's **Connections → Databases → Connections** page.
Use `ignition.database/<connection name>` in a route or select the native connection
in a component's DataSource field. No Camel database binding or integration
resource selection is needed. See [Native Database Connections](../components/databases/).

The native resource types and scripting API are unchanged. Existing Routes
resources appear as integrations, with the same names and YAML files.
`system.camel.request("work-orders", ...)` still addresses the saved integration
name, not an individual route ID. The former Routes, Components, Registry, and
Kamelets page bookmarks are replaced by Integrations and Library.

[Create a Jython route](../camel/examples/) · [system.camel API](../camel/api/)
