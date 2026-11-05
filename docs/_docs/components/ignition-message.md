---
title: ignition-message
templateEngineOverride: md
---
# ignition-message

Send a dictionary payload to an existing Gateway message handler, optionally returning its reply.

Set integration property `messageTarget` to the name of the Ignition project
containing the existing `lookupWorkOrder` Gateway message handler.

```yaml
- route:
    id: lookup-work-order
    from:
      uri: direct:work-order
      steps:
        - to: ignition-message:lookupWorkOrder?project={{messageTarget}}&mode=request&timeoutSec=15
```

Save the integration as `work-order-api`, then call:

```python
order = system.camel.request("work-order-api", "direct:work-order", {"orderId": "WO-1042"})
```

| Option | Default | Meaning |
| --- | --- | --- |
| `project` | Required | Ignition project containing the Gateway message handler. |
| `gateway` | Local Gateway | Remote Gateway Network destination, when needed. |
| `mode` | `auto` | `send`, `request`, or select from the exchange pattern. |
| `timeoutSec` | `10` | Request timeout, 1–300 seconds. |

In `request` mode, the reply replaces the body. In `send` mode, the body stays
unchanged and dispatch results are stored in `ignitionMessageDispatch`.

Native message-handler security still applies. This endpoint is producer-only.
To enter a route from a handler, call `system.camel.send` or
`system.camel.request`.
