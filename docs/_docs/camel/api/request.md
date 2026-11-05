---
title: system.camel.request
---
# system.camel.request

**Scope:** Gateway

Sends synchronously and returns the reply (`InOut`). Timeouts depend on the endpoint/route.

## Syntax

```python
system.camel.request(name, endpoint, payload, headers=None)
# type: (str, str, Any, Optional[Mapping[str, Any]]) -> Any
```

- `name`: saved integration name.
- `endpoint`: destination URI within that integration's context.
- `payload`: JSON-compatible body.
- `headers`: optional mapping of JSON-compatible values.

## Example

Using the [uppercase route](../../examples/routes/):

```python
reply = system.camel.request("demo", "direct:uppercase", "hello")
# reply: "HELLO"
```

Calls can raise exceptions. Replies are converted to Python values.
