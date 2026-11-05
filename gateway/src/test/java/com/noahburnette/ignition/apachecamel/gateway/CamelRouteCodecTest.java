package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import org.junit.jupiter.api.Test;

class CamelRouteCodecTest {
    private static final String YAML = """
        # Exact text survives: café
        - route:
            from:
              uri: direct:test
              steps:
                - to: log:test
        """;
    private final CamelRouteCodec codec = new CamelRouteCodec();

    @Test
    void storesYamlAsADeclaredResourceFileAndJoinsItForTheEditor() throws Exception {
        var builder = builder();
        var config = new CamelRouteResource(YAML);
        codec.encode(config, builder);
        var resource = builder.build();
        assertTrue(resource.getDataKeys().contains("config.camel.yaml"));
        assertEquals(YAML, new String(resource.getData("config.camel.yaml").orElseThrow().getBytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(JsonParser.parseReader(resource.getDataReader("config.json").orElseThrow()).getAsJsonObject().has("yaml"));
        assertEquals(config, codec.decode(resource).config());
        assertSame(resource, codec.decode(resource).resource());
        assertEquals(YAML, codec.encodeToJson(codec.decode(resource).config()).getAsJsonObject().get("yaml").getAsString());
    }

    @Test
    void nativeActionNormalizationSeparatesPrimaryAndBackupFiles() throws Exception {
        var builder = builder().putData("config.json", codec.encodeToJson(new CamelRouteResource(YAML)).toString())
            .putData("backupConfig.json", codec.encodeToJson(new CamelRouteResource(YAML.replace("log:test", "log:backup"))).toString());
        CamelRouteCodec.splitYaml(builder);
        var decoded = codec.decode(builder.build());
        assertEquals(YAML, decoded.config().yaml());
        assertEquals(YAML.replace("log:test", "log:backup"), decoded.backupConfig().orElseThrow().yaml());
        builder.removeData("backupConfig.json");
        CamelRouteCodec.splitYaml(builder);
        assertFalse(builder.build().getDataKeys().contains("backup-config.camel.yaml"));
    }

    @Test
    void restTransportSplitsInlineYamlIntoTheNativeFile() throws Exception {
        var builder = builder().putData("config.json", codec.encodeToJson(new CamelRouteResource(YAML)).toString());
        CamelRouteCodec.splitYaml(builder);
        assertTrue(builder.build().getDataKeys().contains("config.camel.yaml"));
        assertEquals(YAML, codec.decode(builder.build()).config().yaml());
    }

    @Test void storedJsonCannotReplaceTheNativeYamlFile() {
        var builder = builder().putData("config.json", codec.encodeToJson(new CamelRouteResource(YAML)).toString());
        assertThrows(Exception.class, () -> codec.decode(builder.build()));
    }

    @Test
    void fileIsAuthoritativeAndJythonCanRetainAnInactiveYamlDraft() throws Exception {
        var config = new CamelRouteResource("JYTHON", YAML, "Test", "routes.orders");
        var builder = builder();
        codec.encode(config, builder);
        assertEquals(config, codec.decode(builder.build()).config());
        codec.encode(new CamelRouteResource("JYTHON", null, "Test", "routes.orders"), builder);
        assertFalse(builder.build().getDataKeys().contains("config.camel.yaml"));
        assertNull(codec.decode(builder.build()).config().yaml());
        codec.encode(new CamelRouteResource(YAML), builder);
        builder.putData("config.camel.yaml", "invalid: [");
        assertThrows(Exception.class, () -> codec.decode(builder.build()));
    }

    private static com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder builder() {
        return Resource.newBuilder().setResourceCollectionName("core")
            .setResourcePath(new ResourcePath(new ResourceType("test", "camel-route"), "test"));
    }
}
