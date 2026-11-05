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
import com.inductiveautomation.ignition.gateway.config.actions.StatefulModificationResponse;
import com.inductiveautomation.ignition.gateway.config.actions.StructuralModificationResponse;
import com.noahburnette.ignition.apachecamel.camel.CamelKameletResource;
import com.noahburnette.ignition.apachecamel.camel.CamelKamelets;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Kamelet YAML is native resource data, including backup/override configuration. */
public final class CamelKameletCodec extends GsonResourceCodec<CamelKameletResource> {
    public CamelKameletCodec() {
        super(CamelKameletResource.class, JsonResourceCodec.buildGson(null), config -> {
            var errors = com.inductiveautomation.ignition.gateway.config.ValidationErrors.newBuilder();
            config.validationErrors().forEach(errors::addFieldMessage);
            if (config.validationErrors().isEmpty()) try { CamelKamelets.validate(config); }
            catch (Exception failure) { errors.addFieldMessage(config.sourceField(), failure.getMessage()); }
            var result = errors.build();
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        });
    }
    @Override public com.inductiveautomation.ignition.common.gson.JsonElement encodeToJson(CamelKameletResource config) {
        var json = super.encodeToJson(config).getAsJsonObject();
        if (config.yaml() == null) json.add("yaml", com.inductiveautomation.ignition.common.gson.JsonNull.INSTANCE);
        return json;
    }
    @Override public void encode(CamelKameletResource config, ResourceBuilder builder) {
        if (config.yaml() == null) builder.removeData("config.kamelet.yaml");
        super.encode(config, builder);
        splitYaml(builder);
    }
    @Override public DecodedResource<CamelKameletResource> decode(Resource resource) throws DecodingException {
        var joined = resource.toBuilder();
        join(resource, joined, "config.json", "config.kamelet.yaml");
        join(resource, joined, "backupConfig.json", "backup-config.kamelet.yaml");
        var decoded = super.decode(joined.build());
        return new DecodedResource<>() {
            public Resource resource() { return resource; }
            public CamelKameletResource config() { return decoded.config(); }
            public Optional<CamelKameletResource> backupConfig() { return decoded.backupConfig(); }
        };
    }
    private static void join(Resource resource, ResourceBuilder builder, String jsonFile, String yamlFile) {
        if (resource.getData(jsonFile).isEmpty() || resource.getData(yamlFile).isEmpty()) return;
        var json = JsonParser.parseString(text(resource, jsonFile)).getAsJsonObject();
        json.addProperty("yaml", text(resource, yamlFile));
        builder.putData(jsonFile, json.toString());
    }
    static void splitYaml(ResourceBuilder builder) {
        split(builder, "config.json", "config.kamelet.yaml");
        split(builder, "backupConfig.json", "backup-config.kamelet.yaml");
    }
    private static void split(ResourceBuilder builder, String jsonFile, String yamlFile) {
        var resource = builder.build();
        if (resource.getData(jsonFile).isEmpty()) { builder.removeData(yamlFile); return; }
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
        var delegate = ResourceNames.validatingCreate("camel-kamelet");
        return (manager, requests, context) -> {
            if (requests.stream().anyMatch(request -> !CamelKameletResource.validName(request.path().getName())))
                return new StatefulModificationResponse.Failure(new IllegalArgumentException("Kamelet names use 1–64 lowercase letters/digits/hyphens, starting with a letter; source and sink are reserved"));
            return delegate.create(manager, requests.stream().map(request -> new ResourceAction.CreateRequest(
                request.path(), request.collectionName(), request.builder().andThen(CamelKameletCodec::splitYaml))).toList(), context);
        };
    }
    static ResourceAction.Modify modifyAction() {
        var delegate = new DefaultResourceActions.Modify();
        return (manager, requests, context) -> delegate.modify(manager, requests.stream().map(request -> new ResourceAction.ModifyRequest(
            request.path(), request.collectionName(), request.signature(), request.builder().andThen(CamelKameletCodec::splitYaml))).toList(), context);
    }
    static ResourceAction.Rename renameAction() {
        var delegate = ResourceNames.validatingRename("camel-kamelet");
        return (manager, type, oldName, newName, references, actor, context) -> {
            if (!CamelKameletResource.validName(newName)) return new StructuralModificationResponse.Failure(new IllegalArgumentException("Invalid or reserved Kamelet name"));
            return delegate.rename(manager, type, oldName, newName, references, actor, context);
        };
    }
}
