# Route a batch with Camel EIPs

This YAML-only example demonstrates Splitter, Message Filter, Content-Based
Router, and Multicast with visible Ignition tag outputs.

## Files

- Route: [`../eip-to-tags.yaml`](../eip-to-tags.yaml)
- Setup and inspection helper: [`../project/tags.py`](../project/tags.py)
- Installer: `scripts/camel-examples.py`

## Quick start

```sh
python3 scripts/camel-examples.py
```

Edit `[default]CamelExamples/EIP/Input`:

```json
[
  {"sensor":"tank-1","value":42,"enabled":true},
  {"sensor":"tank-2","value":88,"enabled":true},
  {"sensor":"disabled","value":999,"enabled":false}
]
```

## Flow

```text
JSON array
  → Splitter
  → Message Filter: enabled only
  → Content-Based Router: value >= 75
  → High or Normal tag
  → Multicast: LastRouted and AuditLast
```

Expected tags under `[default]CamelExamples/EIP/`:

| Tag | Value |
| --- | --- |
| `Normal` | `tank-1 = 42 (NORMAL)` |
| `High` | `tank-2 = 88 (HIGH)` |
| `LastRouted` | `tank-2 = 88 (HIGH)` |
| `AuditLast` | `tank-2 = 88 (HIGH)` |

The same array can enter through the authenticated HTTP endpoint:

```text
/system/integrations/camel-example-eip/exercise
```

The endpoint returns the JSON string `"processed"` after the batch enters the
shared EIP route.
