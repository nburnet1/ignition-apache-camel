---
title: Integration Examples
---
# Integration Examples

The example source and instructions live together in the repository's
[`examples/camel` folder]({{ sourceRepositoryUrl }}/tree/main/examples/camel).
The Docs UI links to those canonical files instead of duplicating route
definitions here.

## Focused examples

| Example | Camel capability |
| --- | --- |
| [Multiple sources, one model]({{ sourceRepositoryUrl }}/tree/main/examples/camel/multi-source-normalization) | HTTP, MQTT, and tag consumers converge on one service route. |
| [Route a batch with EIPs]({{ sourceRepositoryUrl }}/tree/main/examples/camel/eip-routing) | Splitter, Message Filter, Content-Based Router, and Multicast. |
| [Idempotent Consumer]({{ sourceRepositoryUrl }}/tree/main/examples/camel/idempotent-consumer) | Suppress duplicate command IDs around one protected write. |
| [Reusable Kamelet]({{ sourceRepositoryUrl }}/tree/main/examples/camel/kamelet-temperature) | Reuse one parameterized route template. |
| [Control Bus]({{ sourceRepositoryUrl }}/tree/main/examples/camel/control-bus) | Suspend, resume, and inspect a running route. |

## Copy-and-paste integrations

| Example | Camel capability |
| --- | --- |
| [MQTT to tags]({{ sourceRepositoryUrl }}/tree/main/examples/camel/mqtt-to-tags) | MQTT consumer, JSON unmarshal, Splitter, and dynamic tag writes. |
| [Tags to MQTT]({{ sourceRepositoryUrl }}/tree/main/examples/camel/tags-to-mqtt) | Tag consumer, quality filter, JSON marshal, and MQTT producer. |
| [JSON file to tags]({{ sourceRepositoryUrl }}/tree/main/examples/camel/json-file-to-tags) | File consumer, idempotent file key, JSON unmarshal, and Splitter. |

## Advanced examples

- [Work-order EIP service]({{ sourceRepositoryUrl }}/blob/main/examples/camel/WORK_ORDERS.md)
- [WorkOrders MQTT round-trip]({{ sourceRepositoryUrl }}/blob/main/examples/camel/WORK_ORDERS_MQTT.md)
- [Factory / 64-pattern integration lab]({{ sourceRepositoryUrl }}/tree/main/examples/camel/simulation)

Browse the
[complete examples folder]({{ sourceRepositoryUrl }}/tree/main/examples/camel).

For examples beyond Ignition, see the
[Apache Camel example catalog](https://camel.apache.org/manual/examples.html)
and the official
[`apache/camel-examples` repository](https://github.com/apache/camel-examples).
