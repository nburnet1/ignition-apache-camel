# JSON file to tags

Watch `camel-inbox/readings.json` and write each numeric field to an Ignition
memory tag.

## Setup

1. Create writable `Float8` memory tags:
   - `[default]FILE_temperature`
   - `[default]FILE_humidity`
   - `[default]FILE_pressure`
2. Paste [`route.yaml`](route.yaml) into a YAML integration and enable it.
3. Place this file at `camel-inbox/readings.json`, relative to the Gateway
   process:

```json
{"temperature":24.1,"humidity":40.8,"pressure":101.2}
```

The File consumer detects new or modified content, unmarshals JSON, splits the
fields, and writes the three tags. The modified time is part of the idempotent
file key, so editing the same file processes it again.
