---
title: Introduction
---
# Introduction

Ignition Apache Camel runs [Apache Camel](https://camel.apache.org/) inside the
Ignition Gateway. It gives integration work a managed home in the Gateway
instead of requiring a separate Camel service.

A route starts at an endpoint, does some work with the message, and sends it to
another endpoint. That simple shape covers jobs such as publishing tag changes,
accepting JSON over HTTP, or moving readings from MQTT into Ignition.

**Requires Ignition 8.3.8 or newer.** Scripting is available in Gateway scope.

## Where it fits

Use a route when data needs to cross between Ignition and another system. The
built-in Ignition components can:

- subscribe to and write tags;
- call project scripts;
- send Gateway messages;
- notify Perspective sessions and pages; and
- expose authenticated HTTP endpoints.

Included Camel components support MQTT 5, HTTP, files, FTP, scheduling, JSON,
and CSV. You can install additional compatible components.

## Choose a definition style

Start with YAML for most integrations. Use Gateway-scoped Jython when the route
needs programmatic construction, a custom processor, or a bean factory. Turn
repeated route shapes into Kamelets.

## Next steps

- [Gateway UI guide](getting-started/)
- [Integration examples](integration-examples/)
- [Ignition components](components/)
- [Terminology](terminology/)
- [Gateway scripting API](camel/api/)

For other endpoint types, see the
[Apache Camel component catalog](https://camel.apache.org/components/4.22.x/index.html).
