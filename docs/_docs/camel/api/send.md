---
title: system.camel.send
---
# system.camel.send

**Scope:** Gateway

Sends synchronously without returning a reply (`InOnly`). No durable-delivery guarantee.

## Syntax

```python
system.camel.send(name, endpoint, payload, headers=None)
# type: (str, str, Any, Optional[Mapping[str, Any]]) -> None
```

- `name`: saved integration name.
- `endpoint`: destination URI within that integration's context.
- `payload`: JSON-compatible body.
- `headers`: optional mapping of JSON-compatible values.

## Example

Using the [uppercase route](../../examples/routes/):

```python
system.camel.send("demo", "direct:uppercase", "hello", {"source": "Gateway"})
```

Calls can raise exceptions. Use [request](../request/) when you need the reply.
