package com.noahburnette.ignition.apachecamel.gateway;

import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** One configured component instance. Its resource name is the Camel URI scheme. */
public record CamelComponentResource(String type, Http http, Mqtt mqtt, Quartz quartz, Generic generic) {
    public CamelComponentResource { type = type == null ? "HTTPS" : type; }
    public CamelComponentResource(String type, Http http, Mqtt mqtt, Quartz quartz) { this(type, http, mqtt, quartz, null); }
    public record Generic(String scheme, Map<String, String> options, Map<String, CamelSecretReference> secrets, Map<String, String> references) {
        public Generic {
            options = options == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(options));
            secrets = secrets == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(secrets));
            references = references == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(references));
        }
    }
    public record Http(Integer connectTimeout, Integer responseTimeout, String tls) {
        public Http { connectTimeout = connectTimeout == null ? 5000 : connectTimeout; responseTimeout = responseTimeout == null ? 30000 : responseTimeout; tls = blank(tls); }
    }
    public record Mqtt(String brokerUrl, String username, CamelSecretReference password, String tls,
        Integer qos, Boolean cleanStart, Integer connectionTimeout, Long sessionExpiryInterval) {
        public Mqtt {
            tls = blank(tls); username = blank(username); qos = qos == null ? 1 : qos;
            cleanStart = cleanStart == null || cleanStart; connectionTimeout = connectionTimeout == null ? 10 : connectionTimeout;
            sessionExpiryInterval = sessionExpiryInterval == null ? 0L : sessionExpiryInterval;
        }
    }
    public record Quartz(Integer threads) {
        public Quartz { threads = threads == null ? 2 : threads; }
    }
    public String tlsReference() { return (type.equals("HTTP") || type.equals("HTTPS")) && http != null ? http.tls() : type.equals("MQTT5") && mqtt != null ? mqtt.tls() : null; }
    /** Declared supporting objects, shared by runtime binding and the resource picker. */
    public java.util.List<String> registryReferences() {
        var names = new java.util.LinkedHashSet<String>();
        if (tlsReference() != null) names.add(tlsReference());
        if (generic != null) generic.references().values().stream()
            .filter(name -> IgnitionDatabaseRepository.databaseName(name) == null).forEach(names::add);
        return java.util.List.copyOf(names);
    }
    public Map<String, String> validationErrors() {
        var errors = new LinkedHashMap<String, String>();
        switch (type) {
            case "GENERIC" -> {
                if (generic == null || generic.scheme() == null || !generic.scheme().matches("[a-z][a-z0-9-]*")) errors.put("generic.scheme", "Select an installed component type");
                if (generic != null && java.util.Set.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http", "platform-http", "properties").contains(generic.scheme() == null ? "" : generic.scheme())) errors.put("generic.scheme", "Native Ignition component schemes are reserved");
                if (http != null || mqtt != null || quartz != null) errors.put("type", "Generic settings cannot be combined with focused component settings");
                if (generic != null) {
                    generic.options().forEach((key, value) -> { if (value == null) errors.put("generic.options." + key, "Supply a value or omit the option"); });
                    generic.secrets().forEach((key, ref) -> { if (ref == null || !ref.isValid()) errors.put("generic.secrets." + key, "Select a secret provider and secret"); });
                    generic.references().forEach((key, ref) -> {
                        boolean valid = ref != null && (ref.startsWith(IgnitionDatabaseRepository.PREFIX)
                            ? IgnitionDatabaseRepository.databaseName(ref) != null
                            : ref.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"));
                        if (!valid) errors.put("generic.references." + key, "Select a native Ignition database or Library object");
                    });
                }
            }
            case "HTTP", "HTTPS" -> {
                if (http == null) errors.put("http", "Configure HTTP connection defaults");
                else {
                    if (http.connectTimeout() < 1 || http.connectTimeout() > 300000) errors.put("http.connectTimeout", "Use 1–300000 milliseconds");
                    if (http.responseTimeout() < 1 || http.responseTimeout() > 300000) errors.put("http.responseTimeout", "Use 1–300000 milliseconds");
                    if (type.equals("HTTP") && http.tls() != null) errors.put("http.tls", "Select HTTPS to use TLS");
                }
                if (mqtt != null || quartz != null) errors.put("type", "HTTP settings cannot also configure MQTT or Quartz");
            }
            case "MQTT5" -> {
                if (mqtt == null) errors.put("mqtt", "Configure the broker connection");
                else {
                    try {
                        URI uri = URI.create(mqtt.brokerUrl());
                        if (!java.util.Set.of("tcp", "ssl", "ws", "wss").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                            || uri.getQuery() != null || uri.getFragment() != null) errors.put("mqtt.brokerUrl", "Use a broker URL without credentials, query parameters or fragments");
                        if (mqtt.tls() != null && !java.util.Set.of("ssl", "wss").contains(uri.getScheme())) errors.put("mqtt.tls", "TLS requires ssl:// or wss://");
                    } catch (RuntimeException invalid) { errors.put("mqtt.brokerUrl", "Use tcp://, ssl://, ws:// or wss:// with a broker host"); }
                    if (mqtt.password() != null && !mqtt.password().isValid()) errors.put("mqtt.password", "Select a secret provider and secret");
                    if (mqtt.qos() < 0 || mqtt.qos() > 2) errors.put("mqtt.qos", "Use QoS 0, 1 or 2");
                    if (mqtt.connectionTimeout() < 1 || mqtt.connectionTimeout() > 300) errors.put("mqtt.connectionTimeout", "Use 1–300 seconds");
                    if (mqtt.sessionExpiryInterval() < 0 || mqtt.sessionExpiryInterval() > 4294967295L) errors.put("mqtt.sessionExpiryInterval", "Use 0–4294967295 seconds");
                }
                if (http != null || quartz != null) errors.put("type", "MQTT settings cannot also configure HTTP or Quartz");
            }
            case "QUARTZ" -> {
                if (quartz == null) errors.put("quartz", "Configure the in-memory scheduler");
                else if (quartz.threads() < 1 || quartz.threads() > 16) errors.put("quartz.threads", "Use 1–16 threads");
                if (http != null || mqtt != null) errors.put("type", "Quartz settings cannot also configure HTTP or MQTT");
            }
            default -> errors.put("type", "Choose an installed component type");
        }
        if (!type.equals("GENERIC") && generic != null) errors.put("generic", "Generic settings require the generic component type");
        if (tlsReference() != null && !tlsReference().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) errors.put("type", "Select a named TLS registry entry");
        return errors;
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
