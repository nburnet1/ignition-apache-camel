# WorkOrders → MQTT → typed mirror tags

**Advanced example.** This extends the
[work-order service](WORK_ORDERS.md) with a typed replication protocol.

Send WorkOrders tag values through MQTT and mirror them as typed memory tags.
Run the example on the isolated Gateway at **http://localhost:9288** using the
named MQTT component **`example-mqtt`** and the shared project
**`CamelExamples`**.

Install the [work-order example](WORK_ORDERS.md) first, then run:

```sh
python3 scripts/camel-workorder-mqtt-example.py
```

The installer preserves existing projects, components, orders and route
definitions. It adds the missing `work_order_mqtt` library and leaves the Routes
resource **`camel-example-work-orders-mqtt`** running. It removes temporary
inspection routes after verification.

## How tags reach the mirror

```text
[default]CamelExamples/WorkOrders/**
    │ native wildcard subscription, including initial values and new tags
    ▼
JSON frame: {version, dataType, value, quality, timestamp}
    │ CamelPahoOverrideTopic selects one topic per relative tag path
    ▼
example-mqtt → Mosquitto → camel-demo/work-orders/#
    │ separate MQTT subscriber/client ID, decode frame, provision typed memory tag
    ▼
[default]CamelExamples/WorkOrdersMqttMirror/<same relative tag path>
```

The route is defined in [`work-orders-mqtt.yaml`](work-orders-mqtt.yaml), with
helper code in [`project/work_order_mqtt.py`](project/work_order_mqtt.py),
installed as **`CamelExamples.work_order_mqtt`**. The data passes through the
broker; there is no direct source-to-mirror tag copy. Both endpoints explicitly
use `example-mqtt`. Their broker, credentials and QoS come from that Components
resource (the demo default is QoS 1).

## Inspect the tags

Compare these source tags with their counterparts under the mirror root:

| Source below `WorkOrders` | Destination below `WorkOrdersMqttMirror` |
| --- | --- |
| `Machines/press-01/ActiveWorkOrder` | `Machines/press-01/ActiveWorkOrder` |
| `Machines/press-01/Complete` | `Machines/press-01/Complete` |
| `Dashboard/Completed` | `Dashboard/Completed` |
| `Internal/Ledger` | `Internal/Ledger` |

The mirror preserves Boolean, String and numeric datatypes. JSON held in a String
stays the original String. The current WorkOrders tree contains only these scalar
types; arrays, datasets, documents and other types are rejected rather than
guessed.

The subscriber derives each destination from its topic and can only provision
inside the mirror root. Encoded topic segments preserve case, underscores and
UTF-8. Mirror names allow letters/numbers, underscores, hyphens and spaces.
Path traversal, properties, URI delimiters and wildcard destinations are rejected.

For example, `Machines/press-01/Complete` is published at:

```text
camel-demo/work-orders/_4Dachines/press-01/_43omplete
```

To inspect a retained frame directly from the local broker, run:

```sh
docker compose -f docker/camel-acceptance.yml exec -T mqtt \
  mosquitto_sub -V mqttv5 -h localhost -t 'camel-demo/work-orders/_4Dachines/press-01/_43omplete' -C 1 -W 10
```

To check live updates, edit the harmless **`TransportProbe/Message`**, **`Flag`**
and **`Count`** source tags that the installer creates under WorkOrders, and watch
their mirrors. These probes let you verify updates without changing orders,
machine commands or the ledger.

## Boundaries

- The example publishes **all good-valued atomic WorkOrders tags**, including
  Input, CompletionInput and Internal/Ledger. Use only the demo broker; the
  example does not provide filtering for internal business data or an
  access-control policy.
- Messages are published with retention, so a reconnecting subscriber receives
  the latest frame per topic. This is not a durable event log or an atomic
  multi-tag snapshot.
- The mirror is **outside** WorkOrders. Writing its `Complete` or `Input` tags does
  not execute work orders or publish back to MQTT. There is no feedback loop.
- Mirrored tags are memory values with their own Good quality and timestamps.
  The MQTT frame carries the source quality and timestamps, but these are not
  imposed on the mirror. Bad-quality source notifications are skipped, retaining
  the last good value.
- Deletion is not synchronized: removed tags can leave retained topics and mirror
  tags behind. Existing mirror tags with a conflicting datatype are not overwritten.
- The bounded tag handoff is non-durable. Route diagnostics expose failures and
  rejection counts; stopping the route discards pending source notifications.
