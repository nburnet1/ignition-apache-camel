---
title: Beans
---
# Beans

Add this factory to `camel_demo` to expose an `AtomicInteger` as the named bean
`counter`:

```python
from java.util.concurrent.atomic import AtomicInteger


def counter(config):
    # type: (dict) -> AtomicInteger
    return AtomicInteger(int(config.get("start", "0")))
```

In Gateway **Services → Apache Camel → Library → Advanced objects**, create:

| Field | Value |
| --- | --- |
| Name | `counter` |
| Object type | Bean |
| Project | `MyProject` |
| Factory function | `camel_demo.counter` |
| Arguments | `start` = `0` |

Save and enable the object. In the
[demo integration](../routes/) → **Resources → Advanced objects**, select
`counter`.

Add this second route inside the existing `routes(builder)` function:

```python
    (builder.from_("direct:count")
        .routeId("count")
        .bean("counter", "incrementAndGet"))
```

Save the script and integration. Once `demo` is running:

```python
count = system.camel.request("demo", "direct:count", None)
# count: 1 on the first call
```

The factory receives a dictionary of string arguments. Each referencing integration gets its own bean; its routes share that bean, and this counter resets when recreated.
