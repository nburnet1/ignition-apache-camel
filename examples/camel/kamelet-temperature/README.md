# Reuse a temperature-normalization Kamelet

One parameterized Kamelet is reused for Celsius and Fahrenheit machine inputs.

## Files

- Integration route: [`../kamelet-temperature.yaml`](../kamelet-temperature.yaml)
- Kamelet definition:
  [`../kamelets/normalize-temperature-action.kamelet.yaml`](../kamelets/normalize-temperature-action.kamelet.yaml)
- Project function: [`../project/kamelets.py`](../project/kamelets.py)
- Installer: `scripts/camel-kamelet-example.py`

## Quick start

```sh
python3 scripts/camel-kamelet-example.py --install
```

Write values to:

- `[default]CamelExamples/Kamelets/CelsiusInput`
- `[default]CamelExamples/Kamelets/FahrenheitInput`

Both routes invoke the same Kamelet with different `unit` and `machine`
parameters. Read the unified JSON result from
`[default]CamelExamples/Kamelets/LatestReading`.

`30 C` produces:

```json
{"machine":"Press01","temperatureC":30.0,"status":"NORMAL"}
```

`86 F` produces:

```json
{"machine":"Press02","temperatureC":30.0,"status":"NORMAL"}
```

The default warning threshold is `80 C`. For example, `194 F` becomes:

```json
{"machine":"Press02","temperatureC":90.0,"status":"HIGH"}
```
