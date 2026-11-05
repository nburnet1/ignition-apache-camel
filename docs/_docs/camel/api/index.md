---
title: system.camel
---
# system.camel

**Scope:** Gateway only. `name` is the saved **integration name**, not a DSL `routeId`.
Integrations use the existing native `camel-route` resource type. This UI
organization does not change scripting names, arguments, or return values.

| Function | Purpose |
| --- | --- |
| [list](list/) | List configured resources. |
| [status](status/) | Read runtime status. |
| [restart](restart/) | Queue a restart. |
| [send](send/) | Send without a reply. |
| [request](request/) | Send and return a reply. |
