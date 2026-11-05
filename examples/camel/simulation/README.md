# Camel factory / 64-pattern integration lab

**Capstone lab.** Use the focused examples in [`examples/camel`](../README.md)
before this full acceptance environment.

Use this lab to run a factory work-order simulation, check 64 integration patterns,
and audit all-default-tags MQTT publishing on a local disposable Gateway.

Use only **http://localhost:9288**. Open the operator UI at
**http://localhost:9288/data/perspective/client/global**.

## What runs

- One runnable Ignition project, `global`, with project libraries, Gateway message
  handlers and a native Perspective view. It is not an inheritable project.
- `eip-work-orders`: a Camel timer creates an order every 30 seconds. Persistent
  JMS carries its document to an idempotent PostgreSQL insert. Operators select
  an available order and machine. Database transactions prevent duplicate starts
  and busy-machine overwrites. A one-second machine cycle advances production;
  native completion-tag subscriptions invoke the real Gateway message handler.
- `eip-default-mqtt`: native `[default]**` subscription, including initial values
  and subsequently registered tags, publishes retained QoS 1 JSON frames to
  `ignition/default/<encoded path>`. Values, quality and timestamp are included.
  UTF-8 path segments are percent-encoded; `/` keeps its hierarchy meaning.
  `CamelPahoOverrideTopic` selects the per-tag topic on one publisher.
- `eip-patterns`: real Camel routes plus behavioral assertions. The UI displays
  each pattern's result and evidence, and can rerun the suite.
- `simulation-mqtt` is the managed MQTT component. `eip-jms-factory` is a managed,
  context-owned connection-factory bean. Credentials use a native secret reference.
  JMS and Resilience4j are optional extensions, not new fixed module dependencies.

The lab exercises all five native endpoints: `ignition-tag`, `ignition-script`,
`ignition-message`, `ignition-perspective-message`, and `ignition-gateway-http`.

Perspective uses tag bindings for the dropdown/table and targeted native
notifications for the notification indicator. It uses native tag subscriptions
rather than polling or browsing, and does not substitute another messaging
transport.

### What “64” means

Use [`patterns.json`](patterns.json) as the authoritative checklist: it contains
the 61 messaging patterns in the
[original catalog](https://www.enterpriseintegrationpatterns.com/patterns/messaging/toc.html),
plus Camel's Throttler, Load Balancer and Circuit Breaker. The four general
integration styles are excluded. The lab deliberately selects these patterns;
it does not claim that the current Camel documentation has exactly 64 entries.

Architectural patterns share their relevant running scenario. For example, an
event-driven JMS consumer demonstrates both Event Message and Event-Driven
Consumer; the same verified receipt supports both entries.

Native EIPs implement splitting, aggregation, resequencing, dynamic routing,
enrichment, claim check, wire tap, throttling, load balancing, idempotence and
circuit breaking. Custom processors transform domain data and collect test
observations. They do not replace routing/transaction engines.

## Provisioning

Installing this lab is an **opt-in replacement of the local demos**, not the
ordinary smoke test. Before you start, the existing acceptance Gateway and its
`CamelAcceptance` PostgreSQL connection must already be running.

Keep the existing Gateway container: its resource configuration is container-local.
Do not `down`, recreate, or wipe the Gateway to install this lab.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :gateway:prepareSimulationExtensions
docker compose -f docker/camel-acceptance.yml -f docker/camel-simulation.yml up -d --wait artemis
python3 scripts/camel-eip-simulation.py stage
docker compose -f docker/camel-acceptance.yml restart gateway
python3 scripts/camel-eip-simulation.py preflight
python3 scripts/camel-eip-simulation.py deploy --backup-dir /absolute/path/to/a/new/backup-directory
python3 scripts/camel-eip-simulation.py verify
python3 scripts/camel-eip-simulation.py mqtt_audit
python3 scripts/camel-eip-simulation.py cleanup --backup-dir /absolute/path/to/the/same/backup-directory
```

`deploy` exports every existing project and Camel resource first, disables old
routes before enabling the full-provider publisher, and imports via native APIs.
Keep this order: an old MQTT `#` consumer that creates tags causes a feedback
loop when combined with any all-default-tags publisher.

The backup keeps the original definitions and separately records the disable
signatures intentionally changed during deployment.

`cleanup` requires 64 passing results, detects edits since backup, deletes the
backed-up old routes/components/registry entries, and deletes old projects
children-first. It leaves one `global` project and the three simulation routes.
Existing tags, databases, secret providers and module features are not removed.
No Gateway-owned resource directories are edited.

For subsequent code/UI updates:

```sh
python3 scripts/camel-eip-simulation.py ui
```

The `ui` command replaces the generated `global` project from these sources.
Export any Designer edits before using it.

**Do not run `camel-smoke-test.sh` on this configured instance**: that suite
intentionally reinstalls the old demos.
Use the simulation Compose overlay for later `up` operations so its no-op
project-fixture override prevents re-seeding the old project.

## Verification

```sh
python3 -m unittest discover -s scripts -p 'test_camel_eip_simulation.py'
./gradlew :gateway:test --tests '*EipSimulationSyntaxTest'
python3 scripts/camel-eip-simulation.py verify
python3 scripts/camel-eip-simulation.py mqtt_audit
# With Playwright installed separately:
node scripts/camel-eip-ui-acceptance.cjs
```

You can use `PLAYWRIGHT_MODULE` to name an absolute temporary Playwright
installation. To save the verified browser view, optionally set `EIP_SCREENSHOT`.

The browser acceptance test selects/starts an order, observes machine state and
a native Perspective message, waits for a new dropdown option without reloading,
and clicks Run all 64 checks.

`verify` restarts Artemis between writing and consuming a persistent
message. It also tests Camel/JMS rollback and broker redelivery with exactly one
committed output, TTL, durable subscriptions, concurrent consumers, machine
exclusivity, duplicate completion rejection and the 30-second generator.

PostgreSQL stores the last successful broker-restart proof. UI reruns
reuse that historical proof rather than restarting infrastructure from a page.
Run the command-line verification for a fresh broker-restart proof.

The full-tag audit compares a paginated SHA-256 inventory of every current atomic
tag with retained broker frames, independently of route counters. Folder/UDT
quality events and deleted-tag retained records may add extra topics.

Mosquitto's subscriber queue is bounded at 20,000 messages / 128 MiB so a
full-provider retained replay does not overflow the default 1,000-message limit.
This does not make the publisher or broker unbounded.

## Boundaries

- This is a privileged local test, not a production MES. Perspective requires the
  real module and an active license or trial. A trial expiry is not bypassed.
- Work-order state and audit records persist in PostgreSQL. JMS is persistent in
  the Artemis volume. JMS local transactions do **not** atomically commit SQL, tag
  writes and messages together; the order insert is idempotent for redelivery.
- All-tag MQTT is a live bounded native handoff, **not a durable Gateway outbox**.
  Changes during Gateway/broker downtime or a capacity overflow are not guaranteed
  to replay; a route restart republishes current tag values, not lost transitions.
  The test Mosquitto configuration retains messages in memory, not across broker
  restart. Tag deletion does not automatically remove retained MQTT records.
- The idempotent-consumer pattern's bounded in-memory repository is
  node-local. Do not infer durable exactly-once effects from its test result.
- The dropdown shows available orders among the newest 250 non-completed orders.
  Production history and test audit records remain in the database; this demo
  has no automatic purge.
- The lab broker credentials are local-only examples. Nothing listens on an
  additional host port, and extensions are staged through authenticated APIs.
