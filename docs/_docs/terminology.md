---
title: Terminology
---
# Terminology

| Term | Meaning |
| --- | --- |
| Route | Steps that receive, process, and send messages. |
| Integration | A saved configuration containing one or more routes in one Camel context. Its name is the `name` passed to `system.camel`. |
| Routes resource | The native `camel-route` configuration type behind an integration. Existing resource names, API paths, and YAML files are unchanged. |
| Route ID | Identifies a single route inside a resource, set with `.routeId(...)`. |
| Camel context | The runtime owned by an integration, holding its routes, components, and registry objects. |
| Library | Reusable configuration definitions: connections, certificates, scheduling, advanced objects, and templates. Sharing a definition does not share a live runtime object between integrations. |
| Message | A body and headers passed through a route. |
| Body | The message’s main content. |
| Header | A named message value, such as a source or routing hint. |
| Exchange | Holds the message, exchange properties, and processing results or errors. |
| Endpoint | A source or destination identified by a URI, such as `direct:uppercase`. |
| Consumer / producer | A consumer receives data from an endpoint; a producer sends data to an endpoint. |
| Component | Provides an endpoint type, such as HTTP, MQTT, or SQL. Configure named connection profiles and scheduling settings in Library, then attach them under an integration's Resources. |
| Registry | Camel's lookup mechanism for named objects. Library presents certificates and advanced objects; `ignition.database/<name>` resolves an existing native Ignition database directly. |
| Bean | An object whose methods can be called from a route. |
| Factory | A function that creates and returns an object, such as a registry bean. |
| Processor | Code that reads or changes an exchange. |
| Builder / DSL | The fluent API used to define routes in code. |
| Kamelet | A reusable route template with configurable parameters. |
| `direct:` | An in-memory endpoint for synchronous calls within the same context. |
| InOnly / InOut | InOnly sends without requesting a reply; InOut requests a reply. |
| Extension | A managed JAR that adds Camel components or supporting libraries. |
| Hawtio / Jolokia | Hawtio is a management console. Jolokia provides the HTTP bridge it uses to inspect JMX data. |

[Apache Camel overview](https://camel.apache.org/manual/faq/what-is-camel.html)
