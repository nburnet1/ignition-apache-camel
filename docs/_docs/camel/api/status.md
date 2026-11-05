---
title: system.camel.status
---
# system.camel.status

**Scope:** Gateway

Returns state, diagnostics, route summaries, metrics, and safe startup-failure details. `name` is the saved integration name.

The `components`, `registry`, and `kamelets` fields contain the selected
resources. These API field names retain the native configuration terminology.

## Syntax

```python
system.camel.status(name)
# type: (str) -> Dict[str, Any]
```

## Example

```python
state = system.camel.status("demo")["state"]
```
