---
title: Routes
---
# Routes

In the runnable Ignition project `MyProject`, create a project script named
`camel_demo`. This function connects `direct:uppercase` to the
[Uppercase processor](../processor/):

```python
from com.noahburnette.ignition.apachecamel.camel import JythonRoutes


def routes(builder):
    # type: (JythonRoutes.Builder) -> None
    (builder.from_("direct:uppercase")
        .routeId("uppercase")
        .process(Uppercase()))
```

Use `from_()` because `from` is a Python keyword. Other DSL methods keep their
native Camel names.

In Gateway **Services → Apache Camel → Integrations**, create:

| Field | Value |
| --- | --- |
| Name | `demo` |
| Definition → Language | Jython DSL |
| Project | `MyProject` |
| Function path | `camel_demo.routes` |

Save and enable the integration. Additional routes in this builder share the
integration's context and attached resources.

Call it from Gateway scope:

```python
reply = system.camel.request("demo", "direct:uppercase", "hello")
# reply: "HELLO"
```
