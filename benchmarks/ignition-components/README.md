# Native Ignition Camel component benchmarks

Two complementary suites benchmark the **actual five component implementations**.
The simulation's `py()` / `java()` conversions and module runtime code are not
changed. No commercial module trial is reset or bypassed.

## 1. Adapter-only JMH: explicit lower bounds

`IgnitionComponentAdapterBenchmark` starts real Camel routes and installs the real
`IgnitionComponents` and `IgnitionPlatformHttpEngine`. Its **SDK services are stubs**.
It measures adapter/route overhead and allocation, not Ignition service latency:

- `directBaseline`: direct Camel route as a reference.
- `tagProducerAdapter`: native producer delegates to a stub tag writer.
- `scriptProducerAdapter`: defensive header copy, invocation delegation and result
  replacement; the service does not run Jython or its JSON conversions.
- `messageRequestAdapter` / `messageDispatchAdapter`: endpoint validation and
  request/send dispatch decisions, without native security, workers or messaging.
- `perspectiveDispatchAdapter`: targeted endpoint dispatch, with no real session.
- `httpEngineAdapter`: the real admission semaphore, exchange lifecycle and reply,
  **without** network, Gateway authentication/CSRF or JSON transport serialization.
- `tagCallbackToDelivery`: the real bounded worker handoff and route delivery,
  awaited through a receipt latch. This is not enqueue-only throughput.

All paths include their Camel route overhead. Do not subtract the independent
baseline's percentiles and label the difference an isolated component percentile.
The fixture asserts zero rejected/failed callbacks and settled delivery counts.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :gateway:jmhJar
"$JAVA_HOME/bin/java" -jar gateway/build/libs/gateway-0.2.0-SNAPSHOT-jmh.jar \
  IgnitionComponentAdapterBenchmark -prof gc -foe true \
  -rf json -rff gateway/build/ignition-component-adapters.json
```

Defaults: sample-time mode in microseconds; one thread, two forks, two one-second
warmups and three one-second measurement iterations per fork; padding strings of
128, 4,096 and 32,768 bytes; 256–512 MiB heaps. JMH records distributions and
`gc.alloc.rate.norm` in bytes/operation. The service stubs do not inspect the whole
padding string, so flat payload-size effects belong primarily to the real suite.

The JMH fat-JAR build merges service descriptors and verifies their uniqueness and
the presence of Camel's core converter loaders. Without merging, a fat JAR can
silently load only one of several same-name converter lists even though all
converter classes are present. No converters are manually injected into contexts.

### Recorded adapter-only results

Completed two-fork JMH run, 128-byte padding, **stub SDK services—not live Gateway
latency**. Allocation includes the full measured path, not just the component.

| Adapter path | p50 (µs) | p99 (µs) | Allocation (B/op) |
| --- | ---: | ---: | ---: |
| Direct baseline | 0.458 | 0.625 | 1,760.2 |
| HTTP engine | 0.417 | 0.542 | 1,640.1 |
| Tag producer | 0.458 | 0.625 | 1,784.2 |
| Script producer | 0.500 | 0.709 | 2,328.2 |
| Message request | 0.500 | 0.667 | 1,920.2 |
| Message dispatch | 0.500 | 0.709 | 2,352.2 |
| Perspective dispatch | 0.500 | 0.667 | 1,976.2 |
| Tag callback → worker delivery | 7.368 | 20.029 | Unstable estimate |

The asynchronous tag allocation estimate was **1,058.7 ± 1,908.2 B/op** (JMH's
reported error), so it is not a reliable exact allocation result. Its timing
distribution is recorded separately. Raw results for all three padding sizes are
preserved in `local/component-benchmark-20261030-183952/adapter-jmh.json`.

## 2. Real Ignition 8.3.8 Gateway delivery

`scripts/camel-component-benchmark.py` is restricted to `http://localhost:9288`.
It temporarily adds an owned library, two native Gateway message handlers, one
Perspective page and one Camel resource to the existing runnable `global` project.
Existing scripts/views/settings are preserved. A pre-change export is retained
under the ignored `local/` directory. Normal cleanup removes only unchanged owned
resources and restores the page configuration while preserving other project
changes. Benchmark memory tags remain in their dedicated folder.

This uses **no fake SDK services**:

| Case | Measured boundary |
| --- | --- |
| Direct baseline | Gateway-side template call through a direct Camel route. |
| `ignition-tag.write` | Native write, including the real bounded quality-checked SDK operation. |
| Exact/glob tag delivery | Native write start to correlated native subscription receipt, including SDK dispatch and the component's worker handoff. Write return is reported separately. |
| `ignition-script.echo` | Real project-function resolution/invocation, existing JSON conversions and return; an echo function avoids unrelated database/business work. |
| `ignition-message.request-reply` | Actual local Gateway `system.util` request/reply, security context, bounded owned worker, handler and conversions. |
| `ignition-message.send` | Actual dispatch-return latency and separately correlated Gateway handler delivery latency. |
| `ignition-perspective-message.session` | Actual dispatch-return latency and separately correlated receipt in a targeted live Perspective component message handler. |
| `ignition-gateway-http` | Authenticated/CSRF-protected localhost POST roundtrip, server JSON processing and response bytes, using persistent connections at configured client concurrency. |

**Perspective receipt is Gateway-side component-handler execution, not browser
render completion or a WebSocket/browser network roundtrip.** The handler runs
in an actual instantiated page belonging to the measured session. No synthetic
session UUID or substitute handler is accepted as evidence of native delivery.

Native cases are sequential closed-loop measurements. A unique sequence ID,
payload-length check and receipt latch verify every asynchronous delivery before
the next submission. Timeouts, duplicates, missing receipts, bad tag quality,
payload mismatches and component rejection/failure counters fail the run; they
are not silently excluded from percentile samples.

The fixture runs on a Camel-owned worker, not a Gateway HTTP worker held open for
the whole run. Gateway `System.nanoTime` timestamps are used for native cases;
client `perf_counter_ns` timestamps are used for HTTP. Clocks from different
machines are never subtracted. Payload construction and client JSON encoding
are outside per-operation timing; native component conversions and server JSON
processing remain inside it.

### Run

The existing `global` simulation and Camel module must be running. For all five
components, install Playwright separately and use a real headless page:

```sh
npm install --prefix local/component-benchmark-browser playwright
local/component-benchmark-browser/node_modules/.bin/playwright install chromium
PLAYWRIGHT_MODULE="$PWD/local/component-benchmark-browser/node_modules/playwright" \
  python3 scripts/camel-component-benchmark.py --perspective \
  --samples 1000 --warmup 100 --padding-bytes 128 4096 32768 \
  --http-concurrency 1 4
```

Without `--perspective`, results explicitly say **NOT MEASURED** for Perspective.
With it, unavailable/expired Perspective or a failed native delivery fails the
run rather than resetting a trial or falling back to a mock.

`paddingBytes` is the UTF-8 ASCII padding field's length, not total JSON wire size.
Tag cases use Int64 memory values, not padding strings. Existing concurrency and
64 KiB limits are unchanged. HTTP concurrency is bounded at 16 or less.

### Results and workload interpretation

The output directory contains:

- `global-before.zip`: project export before adding the benchmark fixture.
- `gateway.json`: the Gateway's completed/failed report before HTTP measurements.
- `results.json`: all successful measurements, raw nanosecond samples, p50/p95/p99,
  maximum, mean, sample count, closed-loop rate, resource diagnostics and workload
  configuration. The HTTP results also record client concurrency.

The factory and all-default-tags MQTT publisher remain running. Initial MQTT
snapshot replay is allowed to drain before measurements; normal background
traffic, Docker scheduling, CPU contention, GC and JIT remain part of the live
environment. Adding/removing project resources invokes normal project/Camel
reconciliation; this is not a zero-disruption production benchmark.

Reported closed-loop operations/second are rates for that particular workload,
**not a maximum sustainable throughput claim**. One-in-flight tag/message tests
measure latency without saturating their bounded queues. A separate offered-load
test is needed to establish saturation, rejection thresholds or capacity sizing.
Neither suite proves durable delivery or measures Gateway Network remote peers.

## Correctness checks

```sh
python3 -m unittest discover -s scripts -p 'test_camel_component_benchmark.py'
./gradlew :gateway:test --tests '*IgnitionComponentBenchmarkSyntaxTest'
```

The original simulation conversions are retained; neither suite proposes a
runtime optimization or replaces the real components with the benchmark code.

## Recorded live results

Local Ignition 8.3.8 Docker Gateway on this macOS host, with the factory/MQTT
workload still running; 100 warmups followed by **1,000 samples per native case**.
HTTP concurrency 4 uses 1,000 measured requests **per worker** (4,000 total).
These are individual-run observations, not cross-machine guarantees.

Representative small-body results (128-byte padding; Int64 values for tags):

| Real operation | p50 (µs) | p95 (µs) | p99 (µs) |
| --- | ---: | ---: | ---: |
| Direct route baseline | 2.25 | 9.00 | 12.62 |
| Native tag write, return | 10.71 | 31.17 | 52.88 |
| Native exact tag, write → callback | 70.92 | 99.12 | 124.42 |
| Native glob tag, write → callback | 71.71 | 109.83 | 170.46 |
| Project-script echo, return | 299.38 | 473.00 | 1,034.71 |
| Gateway message request/reply | 363.75 | 604.25 | 962.21 |
| Gateway message send, dispatch return | 94.04 | 143.96 | 213.42 |
| Gateway message send, handler receipt | 341.71 | 536.04 | 1,172.00 |
| Perspective dispatch return | 33.29 | 89.75 | 204.29 |
| Perspective component-handler receipt | 202.67 | 412.75 | 900.96 |
| Authenticated HTTP roundtrip, concurrency 1 | 521.50 | 784.33 | 1,102.42 |
| Authenticated HTTP roundtrip, concurrency 4 | 648.79 | 1,536.38 | 2,244.25 |

The same run also measured 4,096- and 32,768-byte padding. At 32,768 bytes, the
single-client authenticated HTTP p50/p99 were 893.79/3,685.29 µs; four-client
p50/p99 were 1,447.12/5,722.21 µs. Native flat-string cases did not show a monotonic
size increase; sequential phase ordering, warmup and background variance matter,
so those observations are not evidence that larger messages are inherently faster.

**Zero** native failed/rejected deliveries, HTTP failures/rejections, unmatched
callbacks or invalid payloads were reported. Exact/glob observers processed all
2,200 warmup + measured callbacks; tag producers completed 3,300 writes; script
producers completed 3,300 invocations. Native Gateway/Perspective delivery receipts
were correlated individually, not inferred from dispatch success.

The full raw run and pre-change export are under
`local/component-benchmark-20261030-183952/`. This is distinct from the smaller
pilot run. Benchmark routes/handlers/view/library were removed afterward, leaving
the single existing `global` project and factory routes.
