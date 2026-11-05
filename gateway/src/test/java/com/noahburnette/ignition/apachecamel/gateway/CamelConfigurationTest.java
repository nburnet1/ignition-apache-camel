package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamelConfigurationTest {
    @Test void nativeDatabaseReferencesDoNotRequireSavedRegistryResources() {
        var component = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("jdbc", Map.of(), Map.of(),
                Map.of("dataSource", "ignition.database/ERP Main", "otherObject", "saved-object")));
        assertTrue(component.validationErrors().isEmpty());
        assertEquals(List.of("saved-object"), component.registryReferences());
        var invalid = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("jdbc", Map.of(), Map.of(),
                Map.of("dataSource", "ignition.database/")));
        assertTrue(invalid.validationErrors().containsKey("generic.references.dataSource"));
    }
    @Test void dottedDatabaseAliasNamesRetainTheirSavedResourceIdentity() {
        assertTrue(ResourceNames.isValid("ignition.database.Reporting"));
        assertFalse(ResourceNames.isValid("ignition.database/Reporting"));
        var component = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("jdbc", Map.of(), Map.of(),
                Map.of("dataSource", "ignition.database.Reporting")));
        assertTrue(component.validationErrors().isEmpty());
        assertEquals(List.of("ignition.database.Reporting"), component.registryReferences());
    }
    @Test void componentSupportingReferencesIncludeTlsAndDistinctGenericObjects() {
        var https = new CamelComponentResource("HTTPS", new CamelComponentResource.Http(null, null, "trust"), null, null);
        var mqtt = new CamelComponentResource("MQTT5", null,
            new CamelComponentResource.Mqtt("ssl://broker:8883", null, null, "broker-trust", null, null, null, null), null);
        var generic = new CamelComponentResource("GENERIC", null, null, null,
            new CamelComponentResource.Generic("sql", Map.of(), Map.of(),
                Map.of("dataSource", "database", "alternate", "database")));
        assertEquals(List.of("trust"), https.registryReferences());
        assertEquals(List.of("broker-trust"), mqtt.registryReferences());
        assertEquals(List.of("database"), generic.registryReferences());
        assertEquals(List.of(), new CamelComponentResource("QUARTZ", null, null,
            new CamelComponentResource.Quartz(null)).registryReferences());
    }
    @Test void componentsHaveTypeSpecificValidation() {
        assertTrue(new CamelComponentResource("HTTPS", new CamelComponentResource.Http(null, null, "tls"), null, null).validationErrors().isEmpty());
        assertFalse(new CamelComponentResource("HTTP", new CamelComponentResource.Http(null, null, "tls"), null, null).validationErrors().isEmpty());
        assertFalse(new CamelComponentResource("MQTT5", null, new CamelComponentResource.Mqtt("tcp://user:password@host:1883", null, null, null, null, null, null, null), null).validationErrors().isEmpty());
        assertFalse(new CamelComponentResource("MQTT5", null, new CamelComponentResource.Mqtt("tcp://host:1883", null, null, "tls", null, null, null, null), null).validationErrors().isEmpty());
        assertTrue(new CamelComponentResource("QUARTZ", null, null, new CamelComponentResource.Quartz(null)).validationErrors().isEmpty());
        assertFalse(new CamelComponentResource("QUARTZ", null, null, new CamelComponentResource.Quartz(0)).validationErrors().isEmpty());
    }
    @Test void registryEntriesConfigureExactlyOneObject() {
        assertTrue(new CamelRegistryResource("BEAN", new CamelRegistryResource.Bean("project", "library.factory", Map.of("count", "{{start}}"), null), null, null).validationErrors().isEmpty());
        assertTrue(new CamelRegistryResource("TLS", null, new CamelRegistryResource.Tls(null, null, null, null, null, null, null), null).validationErrors().isEmpty());
        assertTrue(new CamelRegistryResource("DATABASE", null, null, "Database").validationErrors().isEmpty());
        assertFalse(new CamelRegistryResource("DATABASE", new CamelRegistryResource.Bean("project", "library.factory", null, null), null, "Database").validationErrors().isEmpty());
        assertFalse(new CamelRegistryResource("TLS", null, new CamelRegistryResource.Tls(null, null, new CamelSecretReference("provider", "store"), null, null, null, null), null).validationErrors().isEmpty());
    }
    @Test void componentSchemesCannotReplaceNativeIgnitionAdapters() {
        assertTrue(CamelConfigurationManager.validComponentName("orders-http"));
        for (String name : new String[]{"WithCaps", "invalid.name", "123", "ignition-tag", "ignition-script", "ignition-gateway-http", "platform-http", "properties"}) assertFalse(CamelConfigurationManager.validComponentName(name));
        for (String scheme : List.of("ignition-gateway-http", "platform-http")) {
            assertFalse(new CamelComponentResource("GENERIC", null, null, null,
                new CamelComponentResource.Generic(scheme, Map.of(), Map.of(), Map.of())).validationErrors().isEmpty());
        }
    }
    @Test void routesExplicitlySelectDistinctImmutableDependencies() {
        var route = new CamelRouteResource("YAML", "routes", null, null, List.of("broker"), List.of("bean"), Map.of("root", "/data"), Map.of("password", new CamelSecretReference("provider", "password")));
        assertTrue(route.validationErrors().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> route.components().add("other"));
        assertFalse(new CamelRouteResource("YAML", "routes", null, null, List.of("broker", "broker"), null, null, null).validationErrors().isEmpty());
    }
}
