# Apache Camel examples for Ignition

Each example keeps its setup, source links, inputs, and expected outputs in its
own README.

## Start here

| Example | Camel capability |
| --- | --- |
| [Multiple sources, one model](multi-source-normalization/README.md) | HTTP, MQTT, and tag consumers converge on one service route and unified model. |
| [Route a batch with EIPs](eip-routing/README.md) | Splitter, Message Filter, Content-Based Router, and Multicast. |
| [Idempotent Consumer](idempotent-consumer/README.md) | Suppress duplicate command IDs around a protected tag write. |
| [Reusable Kamelet](kamelet-temperature/README.md) | Reuse one parameterized route template for two machines. |
| [Control Bus](control-bus/README.md) | Suspend, resume, and inspect a running route. |

## Copy-and-paste integrations

| Example | Camel capability |
| --- | --- |
| [MQTT to tags](mqtt-to-tags/README.md) | MQTT consumer, JSON unmarshal, Splitter, and dynamic tag writes. |
| [Tags to MQTT](tags-to-mqtt/README.md) | Tag consumer, quality filter, JSON marshal, and MQTT producer. |
| [JSON file to tags](json-file-to-tags/README.md) | File consumer, idempotent file key, JSON unmarshal, and Splitter. |

## Advanced examples

- [Work-order EIP service](WORK_ORDERS.md)
- [WorkOrders MQTT round-trip](WORK_ORDERS_MQTT.md)
- [Factory / 64-pattern integration lab](simulation/README.md)

Installers use native Gateway APIs and the shared `CamelExamples` project. They
preserve existing resources and refuse to overwrite conflicting project code.

For examples that are not specific to Ignition, see the
[Apache Camel example catalog](https://camel.apache.org/manual/examples.html)
and the official
[`apache/camel-examples` repository](https://github.com/apache/camel-examples).
