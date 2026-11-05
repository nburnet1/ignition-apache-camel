package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CamelOptionMetadataTest {
    private final com.inductiveautomation.ignition.common.gson.JsonObject schema = JsonParser.parseString("""
        {"componentProperties":{
          "enabled":{"type":"boolean"},"count":{"type":"integer"},
          "mode":{"type":"enum","enum":["A","B"]},
          "password":{"type":"string","secret":true},
          "client":{"type":"object"},"required":{"type":"string","required":true}
        }}
        """).getAsJsonObject();

    @Test void validatesTypesEnumsUnknownOptionsAndRequiredFields() {
        var errors = CamelOptionMetadata.validate(schema, Map.of("enabled", "yes", "count", "1.5", "mode", "C", "unknown", "x"), Set.of(), Set.of());
        assertEquals(Set.of("enabled", "count", "mode", "unknown", "required"), errors.keySet());
    }
    @Test void requiresSecretReferencesAndObjectBindings() {
        var errors = CamelOptionMetadata.validate(schema, Map.of("password", "plaintext", "client", "not an object", "required", "x"), Set.of(), Set.of());
        assertEquals(Set.of("password", "client"), errors.keySet());
        assertTrue(CamelOptionMetadata.validate(schema, Map.of("required", "x", "enabled", "false", "count", "42", "mode", "A"), Set.of("password"), Set.of("client")).isEmpty());
    }

    @Test void rejectsDeprecatedOptionsWithoutRequiringThemOrInspectingValues() {
        var metadata = JsonParser.parseString("{\"componentProperties\":{\"oldOption\":{\"type\":\"string\",\"required\":true,\"deprecated\":true}}}").getAsJsonObject();
        assertTrue(CamelOptionMetadata.validate(metadata, Map.of(), Set.of(), Set.of()).isEmpty());
        var errors = CamelOptionMetadata.validate(metadata, Map.of("oldOption", "private-value"), Set.of(), Set.of());
        assertEquals("Deprecated component option is not supported", errors.get("oldOption"));
        assertFalse(errors.toString().contains("private-value"));
    }
}
