package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.noahburnette.ignition.apachecamel.camel.CamelKameletResource;
import org.junit.jupiter.api.Test;

class CamelKameletCodecTest {
    private final CamelKameletCodec codec = new CamelKameletCodec();
    private final String source = CamelKameletManager.DEFAULT_YAML;
    private com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder builder() {
        return Resource.newBuilder().setResourceCollectionName("core")
            .setResourcePath(new ResourcePath(new ResourceType("test", "camel-kamelet"), "greeting"));
    }
    private CamelKameletResource config(String source) { return new CamelKameletResource("YAML", source, null, null, null); }
    @Test void yamlIsADeclaredNativeFileWithSeparateBackupConfiguration() throws Exception {
        var builder = builder().putData("config.json", codec.encodeToJson(config(source)).toString())
            .putData("backupConfig.json", codec.encodeToJson(config(source.replace("Hello ", "Backup "))).toString());
        CamelKameletCodec.splitYaml(builder);
        var resource = builder.build();
        assertTrue(resource.getDataKeys().contains("config.kamelet.yaml"));
        assertTrue(resource.getDataKeys().contains("backup-config.kamelet.yaml"));
        assertFalse(JsonParser.parseReader(resource.getDataReader("config.json").orElseThrow()).getAsJsonObject().has("yaml"));
        assertEquals(source, codec.decode(resource).config().yaml());
        assertSame(resource, codec.decode(resource).resource());
        assertEquals(source.replace("Hello ", "Backup "), codec.decode(resource).backupConfig().orElseThrow().yaml());
        builder.removeData("backupConfig.json");
        CamelKameletCodec.splitYaml(builder);
        assertFalse(builder.build().getDataKeys().contains("backup-config.kamelet.yaml"));
    }
    @Test void jythonRetainsDraftAndDoesNotExecuteOnSaveAndNullClearsYaml() throws Exception {
        var builder = builder();
        var config = new CamelKameletResource("JYTHON", source, "Nonexistent", "missing.build", null);
        codec.encode(config, builder);
        assertEquals(config, codec.decode(builder.build()).config());
        codec.encode(new CamelKameletResource("JYTHON", null, "Nonexistent", "missing.build", null), builder);
        assertFalse(builder.build().getDataKeys().contains("config.kamelet.yaml"));
        assertNull(codec.decode(builder.build()).config().yaml());
        codec.encode(config(source), builder);
        builder.putData("config.kamelet.yaml", "invalid: [");
        assertThrows(Exception.class, () -> codec.decode(builder.build()));
    }
}
