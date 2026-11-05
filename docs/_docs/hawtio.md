---
title: Hawtio integration
---
# Hawtio integration

[Hawtio](https://hawt.io/docs/) is an external console for inspecting Camel routes and runtime statistics. The module exposes a Jolokia endpoint; it does not embed the console. Integration is disabled by default.

## JVM parameters

Add these options to the **Ignition Gateway JVM**, then restart the Gateway during a planned maintenance window:

```text
-Dapachecamel.camel.hawtio.enabled=true
-Dapachecamel.camel.hawtio.scope=module
-Dapachecamel.camel.hawtio.controlEnabled=false
```

In `ignition.conf`, each option is a `wrapper.java.additional.<n>=-D...` entry. Use unused, consecutive numbers after the existing entries; do not replace existing options.

| Parameter | Values |
| --- | --- |
| `enabled` | `true` enables the endpoint; default `false`. |
| `scope` | `module` inspects this module’s Camel runtimes. `jvm-readonly` allows wider local JVM inspection. Default `module`. |
| `controlEnabled` | Default `false`. In `module` scope, `true` permits supported start, stop, suspend, and resume operations. |

**`jvm-readonly` always denies mutations**, even when `controlEnabled=true`.

## Connect the console

The Gateway endpoint is:

```text
https://<gateway>/data/apache-camel/camel-jolokia
```

Connect external Hawtio through an **authenticated server-side adapter** that maintains a native Gateway session and supplies its CSRF token. Point Hawtio’s remote connection at that adapter’s Jolokia URL, not directly at the Gateway endpoint.

- Jolokia POST requests require native Gateway **WRITE** permission and CSRF protection, including inspection requests.
- GET supports only the version probe and requires **READ** permission.
- Cross-origin browser management is blocked. Do not disable authentication or CSRF to connect a console.

The repository’s `scripts/camel-hawtio-console.sh` provides a **development-only** console/adapter for the dedicated 9488 fixture. It is not a production adapter.
