---
title: ignition-tag
---
# ignition-tag

Subscribe to tag changes with `from`, or write the message body to a tag with `to`. Tags must already exist.

```yaml
- route:
    id: mirror-temperature
    from:
      uri: ignition-tag:[default]Boiler01/TemperatureC?includeInitial=true
      steps:
        - filter:
            simple: '${header.ignitionTagGood} == true'
            steps:
              - to: ignition-tag:[default]Dashboard/BoilerTemperatureC
```

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `includeInitial` | `false` | Deliver the current value when subscribing. |
| `capacity` | `1000` | Pending change-event limit; 1–1,000,000. Full queues reject new events without blocking tag callbacks. |
| `path` | URI path | Alternate path parameter, useful when a glob contains `?`. |

Subscriptions support native tag globs, for example `ignition-tag:[default]Boiler01/**`. Writes require one exact tag path.

## Change-event headers

| Header | Value |
| --- | --- |
| `ignitionTagPath` | Actual changed tag path. |
| `ignitionTagQuality` | Quality description. |
| `ignitionTagGood` | Whether quality is good. |
| `ignitionTagTimestamp` | Source timestamp in epoch milliseconds. |
| `ignitionTagInitial` | Whether this is an initial subscription value. |

The body is the tag value. See
[Tags to MQTT]({{ sourceRepositoryUrl }}/tree/main/examples/camel/tags-to-mqtt)
for publishing it
externally.
