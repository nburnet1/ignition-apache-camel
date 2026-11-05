# Idempotent Consumer

Run the example on **http://localhost:9288**:

```sh
python3 scripts/camel-idempotent-example.py
```

The example protects one tag write with Camel's Idempotent Consumer:

```text
HTTP JSON or Input tag
  → validate {id, value}
  → idempotentConsumer, key = id
       ├─ new ID: write the JSON to LastDelivered
       └─ duplicate ID: skip the protected write
  → write delivered or skipped to LastResult
```

Resources:

- Integration: **`camel-example-idempotent-consumer`**
- YAML: [`../idempotent-consumer.yaml`](../idempotent-consumer.yaml)
- Project script:
  [`../project/idempotent_consumer.py`](../project/idempotent_consumer.py)
- Project library: **`CamelExamples.idempotent_consumer`**
- Registry object: **`idem-message-ids`**, a bounded in-memory repository

There is no telemetry worker or counter dashboard in the basic example. The two
output tags show the behavior directly.

## Try it from tags

Write this JSON to
**`[default]CamelExamples/IdempotentConsumer/Input`**:

```json
{"id":"MSG-1","value":{"machine":"press-01","quantity":10}}
```

Then keep the same ID and change the value:

```json
{"id":"MSG-1","value":{"machine":"press-01","quantity":999}}
```

The second command is skipped:

| Tag | Result |
| --- | --- |
| `LastDelivered` | Still contains quantity `10`. |
| `LastResult` | `skipped` |

Use a new ID such as `MSG-2` and `LastDelivered` changes again.

## HTTP and Gateway scripting

Authenticated JSON POST:

```text
/system/integrations/camel-example-idempotent-consumer/submit
```

The response is the JSON string `"delivered"` or `"skipped"`.

The same route can be called from Gateway scope:

```python
result = system.camel.request(
    "camel-example-idempotent-consumer",
    "direct:idempotent-submit",
    {"id": "MSG-API-1", "value": {"quantity": 5}}
)
```

HTTP, tag, and `system.camel` calls share the repository owned by this
integration's Camel context.

## What the guarantee means

The installer creates a bounded in-memory repository with capacity `1000`:

- keys are local to this integration and Gateway node;
- duplicate IDs are skipped only while their keys remain in the repository;
- capacity eviction makes older IDs eligible again;
- replacing the integration or restarting the Gateway forgets the keys; and
- the repository and protected tag write are not one durable transaction.

`eager: true` reserves an ID before the protected block.
`removeOnFailure: true` releases it if the exchange fails, so a caller can retry.
`completionEager: false` keeps the key tied to the full exchange outcome.

This demonstrates duplicate suppression inside a running Camel context. It does
not promise durable or exactly-once execution. Production durability requires a
persistent repository and failure testing for the protected side effect.

The installer preserves existing definitions and project code. If an older
revision is already installed, review and update its YAML, then keep only the
`idem-message-ids` Registry dependency.
