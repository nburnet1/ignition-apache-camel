# Multiple sources, one model

This example accepts HTTP, MQTT, and Ignition tag inputs, normalizes their
different shapes into one model, annotates the source, and writes the result to
one tag.

## Files

- Route: [`../multi-source-normalization.yaml`](../multi-source-normalization.yaml)
- Project script:
  [`../project/unified_readings.py`](../project/unified_readings.py)
- Installer and verifier: `scripts/camel-examples.py`

## Quick start

Run on the dedicated example Gateway at `http://localhost:9288`:

```sh
python3 scripts/camel-examples.py
```

The installer creates:

- integration `camel-example-unified-readings`;
- MQTT connection `example-mqtt`;
- project library `CamelExamples.unified_readings`;
- `[default]CamelExamples/Unified/RawTemperature`; and
- `[default]CamelExamples/Unified/Latest`.

## Flow

```text
HTTP --\
MQTT --+--
Tag  --/  |
          v
 direct:normalize-reading
          |
          v
 normalize shape and units
          |
          v
 classify and annotate source
          |
          v
     Unified/Latest
```

## Inputs

- HTTP POST to
  `/system/integrations/camel-example-unified-readings/readings`:
  `{"machine":"Press01","temperatureC":30}`
- MQTT topic `camel-demo/readings`:
  `{"device":"Press02","temperatureF":194}`
- Ignition tag:
  write `45` to `[default]CamelExamples/Unified/RawTemperature`

Expected outputs in `[default]CamelExamples/Unified/Latest`:

```json
{"machine":"Press01","temperatureC":30.0,"status":"NORMAL","source":"http"}
{"machine":"Press02","temperatureC":90.0,"status":"HIGH","source":"mqtt"}
{"machine":"Press03","temperatureC":45.0,"status":"NORMAL","source":"ignition-tag"}
```

The route file contains the complete Camel flow. The project script contains the
domain-specific field mapping and unit conversion.
