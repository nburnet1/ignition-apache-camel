---
title: system.camel.restart
---
# system.camel.restart

**Scope:** Gateway

Queues a restart for the named integration and all routes in its context.
Check [status](../status/) for completion. Does not enable a disabled integration.

## Syntax

```python
system.camel.restart(name)
# type: (str) -> None
```

## Example

```python
system.camel.restart("demo")
```
