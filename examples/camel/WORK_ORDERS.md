# Work-order EIP service

**Advanced example.** Start with the focused routes in [Camel examples](README.md)
before using this stateful service.

Follow work orders from submission through machine assignment and completion,
with dashboard tags showing each stage. Run the example on
**http://localhost:9288**:

```sh
python3 scripts/camel-workorder-example.py
```

Routes resource: **`camel-example-work-orders`**  
YAML: [`work-orders.yaml`](work-orders.yaml)  
Project: **`CamelExamples`**, library **`work_orders`** — [`project/work_orders.py`](project/work_orders.py)

For a walkthrough that publishes this entire tag tree through the named MQTT
component and reads it back into a separate mirror, see
[WorkOrders MQTT round-trip](WORK_ORDERS_MQTT.md).

## Follow the EIPs

Orders wait for a batch, then pass through validation and machine assignment.
Completion clears the machine for its next order:

```text
POST /orders or Input tag
          │
          ▼
     SEDA pending queue
          │
          ▼
  Aggregator · every 10 seconds
          │
          ▼
        Splitter
          │
          ▼
  Registry bean · validate + reserve machine
          │
          ▼
        Choice
    ┌─────┼───────────────┐
  Ready  Busy          Invalid
    │     │               │
    │     └─ requeue      └─ rejected
    ▼
  Dynamic tag write → machine ActiveWorkOrder + Status
    │
    └── Wire Tap → SEDA audit → dashboard projection

Complete tag change → one native wildcard subscription
OR CompletionInput tag / POST /complete / system.camel.request
          │
          ▼
   direct:complete-order
          │
          ▼
  Clear active order · set IDLE · reset Complete
          │
          └── Wire Tap → completed/active counts + event history
```

Open **Integrations → camel-example-work-orders → View Details**. Expand
`wo-ten-second-batches`, `wo-validate-and-assign`, `wo-complete-order`, and
`wo-wiretap-dashboard` to see the model steps and counters.

The integration explicitly selects two Camel Registry objects: **`wo-batch`** (Camel's
grouped-body aggregation strategy) and **`wo-service`** (a project-created Camel
`Processor` bean). Edit them under
**Services → Apache Camel → Library → Advanced objects** and attach them under
the integration's **Resources → Advanced objects**.

## Named queue component

The flow uses **`some-queue`**, a configured SEDA component under
**Services → Apache Camel → Library → Connections**. Attach it under the
integration's **Resources → Connections**. The YAML uses that resource name as
its URI scheme rather than the implicit `seda:` component:

| Endpoint | Purpose |
| --- | --- |
| `some-queue:wo-pending` | Pending work before the ten-second aggregator |
| `some-queue:wo-audit` | Wire Tap events consumed by the dashboard projection |

The named component supplies shared defaults, initially `queueSize=1000` and
`concurrentConsumers=2`. Each consumer URI overrides `concurrentConsumers=1` to keep
its processing serialized. Producers specify `waitForTaskToComplete=Never`.
To change the shared defaults, edit the `some-queue` connection under
**Library → Connections** instead of each route URI. Changing the connection
rebuilds dependent integrations.

`some-queue` is a **component instance**, not one global queue. `wo-pending` and
`wo-audit` remain separate endpoint queues created by Camel within this resource's
context. They are memory-only and are not shared between Routes resources. The
installer creates the component only when missing and preserves existing settings.

## Machine folders

Under **`[default]CamelExamples/WorkOrders/Machines/`**:

| Machine | Tags |
| --- | --- |
| `press-01` | `ActiveWorkOrder`, `Status`, `Complete` |
| `lathe-01` | `ActiveWorkOrder`, `Status`, `Complete` |
| `packer-01` | `ActiveWorkOrder`, `Status`, `Complete` |

The demo starts with these machines, but they are not an allowlist. The helper
discovers direct child folders containing `ActiveWorkOrder`, `Status`, and `Complete`.
Machine names use letters, digits, underscores or hyphens (up to 64 characters,
starting with a letter or digit). Add another matching folder without editing or
restarting the YAML.

`ActiveWorkOrder` is a JSON String. `Status` is `ACTIVE` or `IDLE`.
Set the Boolean **`Complete`** tag to **true** and leave it set until acknowledged.

One native **Ignition 8.3.8+** subscription watches `Machines/*/Complete`, including
new machine folders without a route restart. `ignitionTagPath` identifies the actual
changed tag. `work_orders.completionFromTag` captures its active order ID and calls
`system.camel.request`. The signal resets to false after completion. Initial true
signals are included on startup. No polling timer, per-machine YAML or tag-change
event script is required. Keeping the signal true until acknowledged matters:
this is a bounded, non-durable event handoff, not a guaranteed pulse recorder.

Alternatively, write `{"location":"drill-04","id":"WO-2003"}` to the shared String
tag **`[default]CamelExamples/WorkOrders/CompletionInput`**.

Busy machines are not overwritten. Their pending orders return to the queue for
the next ten-second batch boundary.

The validator checks the work-order ID, discovered machine location, integral
quantity from 1 to 100000, and duplicate IDs. It accepts one object or a list of
1–100 objects. Numeric strings such as `"20"` are normalized; extra fields such
as `sku` or nested `custom` values survive in the assigned JSON document. Dynamic
paths stay inside the demo root.

## Try it from tags

Edit **`[default]CamelExamples/WorkOrders/Input`** (String) to:

```json
[{"id":"WO-2001","location":"lathe-01","quantity":20}]
```

Within the next batch, `lathe-01/ActiveWorkOrder` shows `WO-2001`. Set
`lathe-01/Complete` to true, then watch the active order clear and the dashboard's
`Completed` count increase. Use a new ID for each new work order.

## Dashboard tags

Under **`[default]CamelExamples/WorkOrders/Dashboard/`**:

- **Submitted, Queued, Active, Completed, Rejected** — current lifecycle totals.
- **BatchCount, LastBatchSize** — aggregation activity; empty windows do not emit a batch.
- **LastEvent, RecentEvents** — latest event and the last 20 events as JSON.
- **Queue** — pending work orders as JSON.

Because Wire Tap is asynchronous, a single audit consumer recomputes counters from
the current ledger rather than incrementing them once per tap delivery. Event
sequence numbers prevent late audit events from replacing the latest displayed event.

## HTTP endpoints

The same resource exposes these authenticated JSON POST endpoints:

| Path | Body |
| --- | --- |
| `/system/integrations/camel-example-work-orders/orders` | One `{id, location, quantity, ...}` object or an array |
| `/system/integrations/camel-example-work-orders/complete` | `{id, location}` |
| `/system/integrations/camel-example-work-orders/snapshot` | `{}` |

To submit an order, run this in a signed-in Gateway browser console:

```javascript
const session = await fetch('/data/app/session').then(r => r.json());
await fetch('/system/integrations/camel-example-work-orders/orders', {
  method: 'POST',
  headers: {'Content-Type': 'application/json', 'X-CSRF-Token': session.csrfToken},
  body: JSON.stringify([{id: 'WO-2002', location: 'lathe-01', quantity: 30}]),
});
```

Completion rejects a stale/mismatched ID and does not count an already completed
order twice. Both HTTP and tag completion enter `direct:complete-order`.

## Gateway scripting with `system.camel`

Run these examples in **Gateway scope**, such as a project Gateway event script.
The Designer Script Console is not Gateway scope. The running HTTP order/completion
routes use these same APIs through project helpers.

```python
name = "camel-example-work-orders"
work_orders.addMachine("drill-04")  # In CamelExamples; preserves existing tags.

system.camel.request(name, "direct:submit-orders", {
    "id": "WO-2003", "location": "drill-04", "quantity": "3",
    "sku": "widget-A", "custom": {"color": "blue"},
})
# Wait for assignment at the next ten-second batch boundary before completing.
report = system.camel.request(name, "direct:work-order-snapshot", {})
status = system.camel.status(name)

system.camel.request(name, "direct:complete-order", {
    "location": "drill-04", "id": "WO-2003",
})
# Alternative InOnly submission (use a new ID). This is not a durable enqueue.
system.camel.send(name, "direct:submit-orders", {
    "id": "WO-2004", "location": "drill-04", "quantity": 2,
})
```

For callable examples, see [`work-orders-api.py`](work-orders-api.py). `request`
returns the direct route's response. Submission does not wait for validation or
machine assignment.

The wildcard handler calls `system.camel` outside the service bean's lock to avoid
a nested exchange while holding service state.

YAML expression constants are strings, including `constant: 'false'`; the Camel
YAML schema does not accept an unquoted Boolean as a constant expression.

## Initial demonstration

The installer submits [`work-orders-sample.json`](work-orders-sample.json) only
when the ledger is empty. It verifies an initial batch with three active machines,
one deferred order and one rejected order. It completes `WO-1001` through the actual
press completion tag, waits for `WO-1004` to take that slot, then completes `WO-1002`
through HTTP.

After these steps, the demo is ready to inspect:

- **press-01:** `WO-1004` active.
- **lathe-01:** idle, ready for your next submission.
- **packer-01:** `WO-1003` active.
- **Dashboard:** Submitted **5**, Active **2**, Completed **2**, Rejected **1**, Queued **0**.

Rerunning the installer preserves existing scripts, definitions, tags and orders.
Temporary setup/test routes are removed afterward.

This is a visualization demo, not a production work-order system. Queues and the
1000-entry ledger use node-local memory. A memory-tag snapshot recovers queued
entries across a route-context restart, but does not provide Gateway-crash
durability, database transactions or exactly-once machine side effects. The service
only addresses the demo machine folders, never production equipment.
