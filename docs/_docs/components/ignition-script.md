---
title: ignition-script
---
# ignition-script

Call a Jython function with the message body and a copy of its headers. The return value becomes the new body.

Define `normalizeTemperature` in `transforms`:

```python
def normalizeTemperature(payload, meta):
    # type: (object, dict) -> dict
    return {
        "temperatureC": round(float(payload), 2),
        "timestamp": meta.get("ignitionTagTimestamp")
    }
```

Call it from a route:

```yaml
- route:
    id: normalize-temperature
    from:
      uri: direct:normalize-temperature
      steps:
        - to: ignition-script:transforms.normalizeTemperature
```

Save this integration as `temperature-api`, then call it:

```python
reading = system.camel.request("temperature-api", "direct:normalize-temperature", 82.456)
# reading: {"temperatureC": 82.46, "timestamp": None}
```

The function must accept exactly `payload, meta` and be available to Gateway
scripting. Returning `None` sets a null body; it does not stop the route.
Changes to `meta` do not change exchange headers.

## Inline lambdas

Use `ignition-script:inline` for a small Jython transformation:

```yaml
- to:
    uri: ignition-script:inline
    parameters:
      project: MyProject
      expression: "lambda payload, meta: payload * 2"
```

Use a single Jython 2.7 lambda with exactly two plain arguments: body and copied
headers. Defaults, `*args`, and `**kwargs` are not allowed. The expression limit
is 8,192 characters; syntax inspection does not execute it.

The producer compiles the expression at startup using the selected project's
host-owned ScriptManager. Omit `project` to use the Gateway Scripting Project.
Replacing or restarting the producer compiles it again.

Project globals are available. Return values and exceptions behave as they do
for named functions. This is trusted Gateway code, **not a sandbox**.

Camel converts endpoint parameters to a URI. Wrap expressions containing
characters such as `+`, `&`, `%`, `#`, or `?` in `RAW{...}` so URI decoding or
parsing does not alter them before Jython receives them.

Keep credentials out of expressions: route configuration and endpoint URIs
contain the expression, which may appear in standard Camel logs. Use named
project functions for larger transformations.
