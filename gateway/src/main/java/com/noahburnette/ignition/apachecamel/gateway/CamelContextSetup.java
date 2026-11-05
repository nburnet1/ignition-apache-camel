package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.python.core.Py;

/** Per-context ownership, in Camel order: properties, host registry objects, then component instances. */
final class CamelContextSetup implements AutoCloseable {
    private final GatewayContext gateway;
    private final CamelRouteResource route;
    private final Map<String, CamelComponentResource> components;
    private final Map<String, CamelRegistryResource> entries;
    private final com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions;
    private final ArrayList<AutoCloseable> owned = new ArrayList<>();
    private boolean closed;

    CamelContextSetup(GatewayContext gateway, CamelRouteResource route,
        Map<String, CamelComponentResource> components, Map<String, CamelRegistryResource> entries,
        com.noahburnette.ignition.apachecamel.camel.CamelExtensions extensions) {
        this.gateway = gateway; this.route = route; this.components = components; this.entries = entries;
        this.extensions = extensions;
    }
    void install(CamelContext context) {
        var caller = Py.getSystemState();
        try {
            context.getRegistry(org.apache.camel.support.DefaultRegistry.class)
                .addBeanRepository(new IgnitionDatabaseRepository(gateway));
            var properties = new Properties(); properties.putAll(route.properties());
            for (var entry : route.secrets().entrySet()) properties.setProperty("secret." + entry.getKey(), resolveSecret(gateway, entry.getValue()));
            context.getPropertiesComponent().setInitialProperties(properties);
            // Non-bean objects are available to factories, regardless of selection order.
            for (var entry : entries.entrySet()) {
                var config = entry.getValue();
                if (config.type().equals("TLS")) context.getRegistry().bind(entry.getKey(), tls(config.tls()));
                if (config.type().equals("DATABASE")) context.getRegistry().bind(entry.getKey(), datasource(gateway, config.database()));
            }
            for (var entry : entries.entrySet()) if (entry.getValue().type().equals("BEAN")) {
                var factory = entry.getValue().bean();
                var manifest = gateway.getProjectManager().getManifests().get(factory.project());
                if (manifest == null || !manifest.enabled() || manifest.inheritable()) throw new IllegalStateException("Bean factory project must be enabled and runnable");
                var arguments = new com.inductiveautomation.ignition.common.gson.JsonObject();
                factory.arguments().forEach((key, value) -> arguments.addProperty(key, context.resolvePropertyPlaceholders(value)));
                var result = GatewayScriptFunctions.compile(gateway, factory.project(), "def invoke(config):\n    return " + factory.functionPath() + "(config)\n")
                    .invoke(TypeUtilities.gsonToPy(arguments));
                if (result == null || result == Py.None) throw new IllegalArgumentException("Bean factory must return an object");
                Object bean = result.__tojava__(Object.class);
                if (bean == Py.NoConversion) throw new IllegalArgumentException("Bean factory result is not Java-compatible");
                if (factory.closeOnStop() && bean instanceof AutoCloseable closeable && owned.stream().noneMatch(value -> value == closeable)) owned.add(closeable);
                context.getRegistry().bind(entry.getKey(), bean);
            }
            for (var entry : components.entrySet()) installComponent(context, entry.getKey(), entry.getValue());
        } catch (Exception failure) { throw new IllegalStateException("Camel component/registry setup failed (see cause in Gateway logs)", failure); }
        finally { Py.setSystemState(caller); }
    }
    private SSLContextParameters tls(CamelRegistryResource.Tls config) throws Exception {
        var resolved = new LinkedHashMap<CamelSecretReference, String>();
        CamelSecretReference[] references = {config.trustStore(), config.trustStorePassword(), config.keyStore(), config.keyStorePassword(), config.privateKeyPassword()};
        for (var reference : references) if (reference != null && !resolved.containsKey(reference)) resolved.put(reference, resolveSecret(gateway, reference));
        return CamelTls.create(config, resolved);
    }
    private void installComponent(CamelContext context, String name, CamelComponentResource config) throws Exception {
        switch (config.type()) {
            case "GENERIC" -> {
                var generic = config.generic();
                if (java.util.Set.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http", "platform-http", "properties").contains(generic.scheme())) throw new IllegalArgumentException("Native Ignition schemes cannot be configured as generic components");
                var errors = com.noahburnette.ignition.apachecamel.camel.CamelOptionMetadata.validate(extensions.schema(generic.scheme()), generic.options(), generic.secrets().keySet(), generic.references().keySet());
                if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid component configuration: " + errors);
                var component = CamelOptionalComponents.create(context, generic.scheme());
                if (component == null) throw new IllegalArgumentException("Component could not be discovered");
                component.setCamelContext(context);
                var values = new LinkedHashMap<String, Object>();
                generic.options().forEach((key, value) -> values.put(key, context.resolvePropertyPlaceholders(value)));
                for (var entry : generic.secrets().entrySet()) values.put(entry.getKey(), resolveSecret(gateway, entry.getValue()));
                for (var entry : generic.references().entrySet()) {
                    Object value = context.getRegistry().lookupByName(entry.getValue());
                    if (value == null) throw new org.apache.camel.NoSuchBeanException(entry.getValue());
                    values.put(entry.getKey(), value);
                }
                try {
                    org.apache.camel.support.PropertyBindingSupport.build().withCamelContext(context).withTarget(component)
                        .withProperties(values).withMandatory(true).withNesting(false).withReference(false).bind();
                    context.addComponent(name, component);
                } catch (Exception | LinkageError failure) {
                    try { component.stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            case "HTTP", "HTTPS" -> {
                var http = CamelOptionalComponents.create(context, config.type().toLowerCase(java.util.Locale.ROOT));
                var settings = new LinkedHashMap<String, Object>();
                settings.put("connectTimeout", config.http().connectTimeout());
                settings.put("responseTimeout", config.http().responseTimeout());
                if (config.http().tls() != null) settings.put("sslContextParameters", context.getRegistry().lookupByNameAndType(config.http().tls(), SSLContextParameters.class));
                org.apache.camel.support.PropertyBindingSupport.bindProperties(context, http, settings);
                context.addComponent(name, http);
            }
            case "MQTT5" -> {
                var mqtt = config.mqtt();
                var tls = mqtt.tls() == null ? null : context.getRegistry().lookupByNameAndType(mqtt.tls(), SSLContextParameters.class);
                if (mqtt.tls() != null && tls == null) throw new IllegalStateException("MQTT TLS registry entry is unavailable");
                context.addComponent(name, CamelMqtt5.create(context, mqtt,
                    mqtt.password() == null ? null : resolveSecret(gateway, mqtt.password()), tls));
            }
            case "QUARTZ" -> {
                var quartz = CamelOptionalComponents.create(context, "quartz");
                org.apache.camel.support.PropertyBindingSupport.bindProperties(context, quartz, new LinkedHashMap<>(Map.of("enableJmx", false,
                    "properties", Map.of("org.quartz.threadPool.threadCount", String.valueOf(config.quartz().threads()),
                        "org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore"))));
                context.addComponent(name, quartz);
            }
            default -> throw new IllegalArgumentException("Unsupported configured component type");
        }
    }

    static String resolveSecret(GatewayContext gateway, CamelSecretReference ref) throws Exception {
        try (var plaintext = Secret.create(gateway, SecretConfig.referenced(ref.providerName(), ref.secretName())).getPlaintext()) {
            if (plaintext == null) throw new IllegalStateException("Referenced secret is unavailable");
            return plaintext.getAsString();
        }
    }
    /** Borrow from Ignition on every use; no password copy or private connection pool. */
    static DataSource datasource(GatewayContext gateway, String database) {
        return new DataSource() {
            public Connection getConnection() throws SQLException { return gateway.getDatasourceManager().getConnection(database); }
            public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException("Configure credentials on the Ignition database connection"); }
            public PrintWriter getLogWriter() { return null; }
            public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
            public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
            public int getLoginTimeout() { return 0; }
            public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            public <T> T unwrap(Class<T> type) throws SQLException { if (type.isInstance(this)) return type.cast(this); throw new SQLException("Not a wrapper for requested type"); }
            public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
        };
    }
    @Override public synchronized void close() throws Exception {
        if (closed) return; closed = true;
        var caller = Py.getSystemState(); Exception failure = null;
        try {
            for (int index = owned.size() - 1; index >= 0; index--) try { owned.get(index).close(); }
            catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            owned.clear(); if (failure != null) throw failure;
        } finally { Py.setSystemState(caller); }
    }
}
