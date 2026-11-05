# Ignition Apache Camel

- Standalone Gateway module: JDK 17, Ignition SDK/minimum runtime 8.3.8, Camel 4.22.2.
- Run `./gradlew clean build`, Python Camel tests, and `npm --prefix docs run docs:build`.
- Source namespace/module ID: `com.noahburnette.ignition.apachecamel`.
- Preserve user changes and do not commit/push without instruction.
- Never edit Gateway-owned resource files or copy live Gateway state. Provision through native APIs only.
- Acceptance is opt-in, on the new dedicated 9288 Gateway; Hawtio uses 9488. Never deploy to or restart existing 9188/9388/developer fixtures without explicit authorization.
- Routes, Kamelets, Components and Registry are separate native configuration surfaces. Preserve resource codecs, native YAML files, targeted/transitive reconciliation and host-owned project managers.
- Validate YAML structurally without starting consumers; never execute user builders/factories or resolve secrets during validation/dependency inspection.
- Extensions are trusted, bounded, WRITE/CSRF-protected, restart-only managed JARs. Preserve service descriptor merging, private extension loading and Gateway-owned logging; run packagedCamelTest.
- Native HTTP consumer URIs define exact public paths at `/system/integrations`; no mapping table or resource-name expansion. Explicit native security levels, deny-all default, API-key/session authentication and browser CSRF remain mandatory. Do not grant configuration access to invocation-only clients. Keep JSON/concurrency bounds and identity-safe duplicate path ownership.
- Keep native tag glob subscriptions and cold-prefix anchoring; callbacks use bounded non-blocking handoff. Never create private Jython interpreters or close host-owned managers/pools.
- Quartz scheduling is in-memory only. Do not add database schema provisioning, persistent job stores or downtime recovery to the module.
- Diagnostics keep only bounded error metadata, never payloads, headers, messages, full URIs or replay controls. Dependency analysis is static and labels uncertainty. Manifests are not backups/importers.
- Hawtio stays opt-in. JVM-readonly denies all writes/mutations; native access controls are never bypassed for external consoles.
- Update scripting documentation/stubs and API docs with behavior changes. UI stays compact, keyboard accessible, and preserves per-mode drafts.
