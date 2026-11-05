# Ignition Apache Camel

Run Apache Camel routes inside Ignition **8.3.8+**.

The module adds a Gateway-managed runtime for integration work. Routes can react
to Ignition events, transform data, talk to external systems, and call back into
Ignition.

## Features

- Define routes in YAML or Gateway-scoped Jython.
- Read and write Ignition tags.
- Call project scripts.
- Send Gateway and Perspective messages.
- Expose authenticated HTTP endpoints.
- Connect through MQTT 5, HTTP, files, schedules, and other compatible Camel components.
- View route status and diagnostics in the Gateway.

## Start here

1. Install the signed module on an Ignition 8.3.8 or newer Gateway.
2. Open **Services → Apache Camel**.
3. Open **Integrations**, then create and enable an integration.

Each integration owns a Camel context with one or more routes. **Library** holds
reusable connections, certificates, scheduling settings, objects, and templates.
**Extensions** manages installed Camel libraries.

## Documentation

- [Introduction](docs/_docs/index.md)
- [Gateway UI](docs/_docs/getting-started.md)
- [Integration examples](docs/_docs/integration-examples/index.md)
- [Ignition components](docs/_docs/components/index.md)
- [Terminology](docs/_docs/terminology.md)
- [Jython definitions](docs/_docs/camel/examples/index.md)
- [`system.camel` API](docs/_docs/camel/api/index.md)
- [Apache Camel component catalog](https://camel.apache.org/components/4.22.x/index.html)

## Compatibility

| Ignition | Apache Camel | Scope |
| --- | --- | --- |
| 8.3.8+ | 4.22.2 | Gateway |
