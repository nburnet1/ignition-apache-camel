# Tags to MQTT

Publish good-quality tag changes from `[default]ToMqtt/*` to MQTT topic
`ignition/tags`.

## Setup

1. In **Library → Connections**, create an MQTT 5 connection named
   `plant-mqtt`, then attach it to a YAML integration.
2. Create folder `[default]ToMqtt`.
3. Create writable `Float8` memory tag
   `[default]ToMqtt/temperature` with initial value `21.5`.
4. Paste [`route.yaml`](route.yaml) into the integration and enable it.

The initial value is published at startup. Later changes publish JSON containing
the tag path, value, and timestamp.

Example payload:

```json
{"tag":"[default]ToMqtt/temperature","value":21.5,"timestamp":1793790000000}
```
