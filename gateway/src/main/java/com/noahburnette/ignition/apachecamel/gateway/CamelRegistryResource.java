package com.noahburnette.ignition.apachecamel.gateway;

import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One named host-supplied object, instantiated separately in each referencing context. */
public record CamelRegistryResource(String type, Bean bean, Tls tls, String database) {
    public CamelRegistryResource { type = type == null ? "BEAN" : type; }
    public record Bean(String project, String functionPath, Map<String, String> arguments, Boolean closeOnStop) {
        public Bean {
            arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            closeOnStop = closeOnStop == null || closeOnStop;
        }
    }
    public record Tls(String storeType, String protocol, CamelSecretReference trustStore,
        CamelSecretReference trustStorePassword, CamelSecretReference keyStore,
        CamelSecretReference keyStorePassword, CamelSecretReference privateKeyPassword) {
        public Tls { storeType = storeType == null ? "PKCS12" : storeType; protocol = protocol == null ? "TLS" : protocol; }
    }
    public Map<String, String> validationErrors() {
        var errors = new LinkedHashMap<String, String>();
        switch (type) {
            case "BEAN" -> {
                if (bean == null) errors.put("bean", "Configure a project bean factory");
                else {
                    if (bean.project() == null || bean.project().isBlank()) errors.put("bean.project", "Select a runnable project");
                    if (bean.functionPath() == null || !bean.functionPath().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) errors.put("bean.functionPath", "Use library.function");
                    if (bean.arguments().size() > 64 || bean.arguments().entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().length() > 4096)) errors.put("bean.arguments", "Use up to 64 named string arguments");
                }
                if (tls != null || database != null) errors.put("type", "A bean entry cannot also configure TLS or a database");
            }
            case "TLS" -> {
                if (tls == null) errors.put("tls", "Configure TLS trust/key material, or use JVM defaults");
                else {
                    if (!java.util.Set.of("PKCS12", "JKS").contains(tls.storeType())) errors.put("tls.storeType", "Use PKCS12 or JKS");
                    if (!java.util.Set.of("TLS", "TLSv1.2", "TLSv1.3").contains(tls.protocol())) errors.put("tls.protocol", "Use TLS, TLSv1.2 or TLSv1.3");
                    var references = new CamelSecretReference[]{tls.trustStore(), tls.trustStorePassword(), tls.keyStore(), tls.keyStorePassword(), tls.privateKeyPassword()};
                    for (var ref : references) if (ref != null && !ref.isValid()) errors.put("tls", "Select a provider and secret for each configured reference");
                    if (tls.trustStore() != null && tls.trustStorePassword() == null) errors.put("tls.trustStorePassword", "Select the truststore password secret");
                    if (tls.keyStore() != null && tls.keyStorePassword() == null) errors.put("tls.keyStorePassword", "Select the keystore password secret");
                    if (tls.trustStore() == null && tls.trustStorePassword() != null || tls.keyStore() == null && (tls.keyStorePassword() != null || tls.privateKeyPassword() != null)) errors.put("tls", "Select the store before configuring its password");
                }
                if (bean != null || database != null) errors.put("type", "A TLS entry cannot also configure a bean or database");
            }
            case "DATABASE" -> {
                if (database == null || database.isBlank()) errors.put("database", "Select an Ignition database connection");
                if (bean != null || tls != null) errors.put("type", "A database entry cannot also configure a bean or TLS");
            }
            default -> errors.put("type", "Choose Bean, TLS or Database");
        }
        return errors;
    }
}
