package com.noahburnette.ignition.apachecamel.camel;

import java.util.List;

/** The supported bundle, not a claim that every transitive Camel component is supported. */
public final class CamelComponentCatalog {
    private CamelComponentCatalog() { }
    public static final String VERSION = "4.22.2";
    public record Entry(String artifact, List<String> components, List<String> languages,
                        List<String> dataFormats, String setup) { }
    public static List<Entry> entries() {
        return List.of(
            new Entry("camel-direct", List.of("direct"), List.of(), List.of(), "Context-local synchronous routing"),
            new Entry("camel-kamelet", List.of("kamelet"), List.of(), List.of(), "Reusable YAML or project-library Jython templates selected through Kamelets resources; no automatic dependency downloads"),
            new Entry("camel-seda", List.of("seda"), List.of(), List.of(), "Bounded node-local queue; volatile, not durable"),
            new Entry("camel-log", List.of("log"), List.of(), List.of(), "Avoid logging credentials or sensitive payloads"),
            new Entry("camel-timer", List.of("timer"), List.of(), List.of(), "Interval timer"),
            new Entry("camel-scheduler", List.of("scheduler"), List.of(), List.of(), "Scheduled executor; node-local"),
            new Entry("camel-quartz", List.of("quartz"), List.of(), List.of(), "In-memory scheduling; configured routes recreate their schedules after restart. No persistent triggers, database setup or downtime recovery"),
            new Entry("camel-bean", List.of("bean"), List.of("bean"), List.of(), "Reference a bean explicitly registered in this Camel context"),
            new Entry("camel-file", List.of("file"), List.of(), List.of(), "Gateway filesystem and OS permissions; no sandbox"),
            new Entry("camel-ftp", List.of("ftp", "ftps", "sftp"), List.of(), List.of(), "Server credentials, TLS trust or SSH host-key verification must be configured"),
            new Entry("camel-http", List.of("http", "https"), List.of(), List.of(), "Outgoing HTTP; configure timeouts, TLS and authentication"),
            new Entry("camel-platform-http", List.of(), List.of(), List.of(), "Internal engine library for ignition-gateway-http: authenticated POST/JSON, exact public paths, 64 KiB and 16 concurrent requests"),
            new Entry("camel-jackson", List.of(), List.of(), List.of("jackson"), "JSON data format; keep polymorphic type handling disabled for untrusted input"),
            new Entry("camel-jsonpath", List.of(), List.of("jsonpath"), List.of(), "JSONPath expressions"),
            new Entry("camel-csv", List.of(), List.of(), List.of("csv"), "CSV data format; configure delimiters and headers"),
            new Entry("camel-paho-mqtt5", List.of("paho-mqtt5"), List.of(), List.of(), "MQTT 5; configure broker, unique client IDs, QoS, clean start, session expiry and TLS/authentication. No durable Gateway delivery guarantee"),
            new Entry("module-native", List.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http"), List.of(), List.of(), "Gateway tag, project-script, native messaging, optional Perspective notifications and authenticated HTTP integrations")
        );
    }
}
