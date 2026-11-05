# MQTT to tags

Map keys in a JSON message on `factory/readings` to three Ignition memory tags.

## Setup

1. In **Library → Connections**, create an MQTT 5 connection named
   `plant-mqtt`, then attach it to a YAML integration.
2. Create writable `Float8` memory tags:
   - `[default]MQTT_temperature`
   - `[default]MQTT_humidity`
   - `[default]MQTT_pressure`
3. Paste [`route.yaml`](route.yaml) into the integration and enable it.

## Try it

Publish:

```json
{"temperature":23.5,"humidity":41.2,"pressure":101.3}
```

The route unmarshals JSON, turns the object into `{path, value}` rows, splits
them, and writes the three tags dynamically.

Expected values:

- `MQTT_temperature` = `23.5`
- `MQTT_humidity` = `41.2`
- `MQTT_pressure` = `101.3`
