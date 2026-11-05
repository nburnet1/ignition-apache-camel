package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.DecodingException;
import com.inductiveautomation.ignition.gateway.config.GsonResourceCodec;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import com.inductiveautomation.ignition.gateway.config.actions.DefaultResourceActions;
import com.inductiveautomation.ignition.gateway.config.actions.ResourceAction;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** JSON is the editor transport; YAML is an Ignition-managed resource data file. */
public final class CamelRouteCodec extends GsonResourceCodec<CamelRouteResource> {
    private static final String YAML_FILE = "config.camel.yaml";
    private static final String BACKUP_YAML_FILE = "backup-config.camel.yaml";
    public CamelRouteCodec() {
        super(CamelRouteResource.class, JsonResourceCodec.buildGson(null), config -> {
            var errors = com.inductiveautomation.ignition.gateway.config.ValidationErrors.newBuilder();
            config.validationErrors().forEach(errors::addFieldMessage);
            if (config.validationErrors().isEmpty()) {
                try {
                    com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime.validate(config);
                } catch (Exception error) {
                    errors.addFieldMessage(config.sourceField(), error.getMessage());
                }
            }
            var result = errors.build();
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        });
    }

    @Override
    public com.inductiveautomation.ignition.common.gson.JsonElement encodeToJson(CamelRouteResource config) {
        var json = super.encodeToJson(config).getAsJsonObject();
        if (config.yaml() == null) json.add("yaml", com.inductiveautomation.ignition.common.gson.JsonNull.INSTANCE);
        return json;
    }

    @Override
    public void encode(CamelRouteResource config, ResourceBuilder builder) {
        if (config.yaml() == null) {
            builder.removeData(YAML_FILE);
        }
        super.encode(config, builder);
        splitYaml(builder);
    }

    @Override
    public DecodedResource<CamelRouteResource> decode(Resource resource) throws DecodingException {
        var joined = resource.toBuilder();
        join(resource, joined, "config.json", YAML_FILE);
        join(resource, joined, "backupConfig.json", BACKUP_YAML_FILE);
        var decoded = super.decode(joined.build());
        return new DecodedResource<>() {
            public Resource resource() { return resource; }
            public CamelRouteResource config() { return decoded.config(); }
            public Optional<CamelRouteResource> backupConfig() { return decoded.backupConfig(); }
        };
    }

    private static void join(Resource resource, ResourceBuilder builder, String jsonFile, String yamlFile) {
        if (resource.getData(jsonFile).isEmpty()) return;
        var json = JsonParser.parseString(text(resource, jsonFile)).getAsJsonObject();
        json.remove("yaml");
        if (resource.getData(yamlFile).isPresent()) json.addProperty("yaml", text(resource, yamlFile));
        builder.putData(jsonFile, json.toString());
    }

    /** Also used by native REST actions, which write JSON without calling ResourceCodec.encode. */
    static void splitYaml(ResourceBuilder builder) {
        split(builder, "config.json", YAML_FILE);
        split(builder, "backupConfig.json", BACKUP_YAML_FILE);
    }

    private static void split(ResourceBuilder builder, String jsonFile, String yamlFile) {
        var resource = builder.build();
        if (resource.getData(jsonFile).isEmpty()) {
            builder.removeData(yamlFile);
            return;
        }
        var json = JsonParser.parseString(text(resource, jsonFile)).getAsJsonObject();
        if (json.has("yaml")) {
            var yaml = json.remove("yaml");
            if (yaml.isJsonNull()) builder.removeData(yamlFile);
            else builder.putData(yamlFile, yaml.getAsString());
            builder.putData(jsonFile, json.toString());
        }
    }

    private static String text(Resource resource, String file) {
        return new String(resource.getData(file).orElseThrow().getBytes(), StandardCharsets.UTF_8);
    }

    static ResourceAction.Create createAction() {
        var delegate = ResourceNames.validatingCreate("camel-route");
        return (manager, requests, context) -> delegate.create(manager, requests.stream().map(request ->
            new ResourceAction.CreateRequest(request.path(), request.collectionName(),
                request.builder().andThen(CamelRouteCodec::splitYaml))).toList(), context);
    }

    static ResourceAction.Modify modifyAction() {
        var delegate = new DefaultResourceActions.Modify();
        return (manager, requests, context) -> delegate.modify(manager, requests.stream().map(request ->
            new ResourceAction.ModifyRequest(request.path(), request.collectionName(), request.signature(),
                request.builder().andThen(CamelRouteCodec::splitYaml))).toList(), context);
    }
}
