---
title: ignition-perspective-message
templateEngineOverride: md
---
# ignition-perspective-message

Send a dictionary payload to an existing Perspective message handler—for example, to refresh a work-order view after an update.

Set route property `operatorSession` to the intended active Perspective session UUID.

```yaml
- route:
    id: refresh-work-orders
    from:
      uri: direct:refresh-work-orders
      steps:
        - to: ignition-perspective-message:refreshWorkOrders?sessionId={{operatorSession}}&scope=session
```

Save the integration as `operator-notifications`, then call:

```python
system.camel.send("operator-notifications", "direct:refresh-work-orders", {"orderId": "WO-1042"})
```

| Option | Default | Meaning |
| --- | --- | --- |
| `sessionId` | Required | Destination session UUID. |
| `scope` | `page` | `session` or `page`; match the receiver’s listening scope. |
| `pageId` | Required for page scope | Destination page ID; omit for session scope. |

For page delivery, use `scope=page&pageId={{operatorPage}}` and define
`operatorPage`. Gateway routes have no current-view scope.

This endpoint is producer-only. Delivery returns no handler reply and leaves
the body unchanged.
