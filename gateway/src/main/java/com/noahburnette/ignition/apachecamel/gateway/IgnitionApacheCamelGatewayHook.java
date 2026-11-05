package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.entity.Entity;
import com.inductiveautomation.ignition.gateway.model.entity.EntityBuilder;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import java.util.List;
import java.util.Optional;

public final class IgnitionApacheCamelGatewayHook extends AbstractGatewayModuleHook {
    public static final String MODULE_ID = "com.noahburnette.ignition.apachecamel";
    private static final SystemJsModule WEB_UI_MODULE = new SystemJsModule(
        MODULE_ID, "/res/apache-camel/apacheCamel.js");
    private GatewayContext context;
    private CamelRouteResourceManager camelRouteResourceManager;

    @Override public void setup(GatewayContext context) {
        this.context = context;
        var registry = context.getConfigurationManager().getResourceTypeMetaRegistry();
        registry.register(CamelRouteResourceManager.META);
        registry.register(CamelKameletManager.META);
        camelRouteResourceManager = new CamelRouteResourceManager(context);
        registry.register(camelRouteResourceManager.componentMeta());
        registry.register(CamelConfigurationManager.REGISTRY_META);
        context.getWebResourceManager().getSystemJsModuleRegistry().add(WEB_UI_MODULE);
        context.getWebResourceManager()
            .getNavigationModel()
            .getServices()
            .addCategory("apache-camel", category -> category
                .label("Apache Camel")
                .addPage("Integrations", page -> page
                    .position(10)
                    .mount("/services/apache-camel/integrations", "CamelIntegrationsPage", WEB_UI_MODULE)
                    .addAssociatedResourceType(CamelRouteResourceManager.RESOURCE_TYPE)
                )
                .addPage("Library", page -> page
                    .position(20)
                    .mount("/services/apache-camel/library", "CamelLibraryPage", WEB_UI_MODULE)
                    .addAssociatedResourceType(CamelConfigurationManager.COMPONENT)
                    .addAssociatedResourceType(CamelKameletManager.TYPE)
                    .addAssociatedResourceType(CamelConfigurationManager.REGISTRY)
                )
                .addPage("Extensions", page -> page
                    .position(40)
                    .mount("/services/apache-camel/extensions", "CamelExtensionsPage", WEB_UI_MODULE)
                )
            );

        registerCamelEntity();
    }

    @Override public void startup(LicenseState activationState) {
        camelRouteResourceManager.startup();
    }

    @Override public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);
        CamelScriptClassLoading.initialize(manager, camelRouteResourceManager == null
            ? getClass().getClassLoader() : camelRouteResourceManager.extensionLoader());
        manager.addScriptModule("system.camel", new CamelScriptModule(camelRouteResourceManager), new PropertiesFileDocProvider());
    }

    @Override public void mountRouteHandlers(RouteGroup routes) {
        if (camelRouteResourceManager != null) camelRouteResourceManager.mount(routes);
    }

    @Override public void shutdown() {
        if (camelRouteResourceManager != null) {
            camelRouteResourceManager.shutdown();
            camelRouteResourceManager = null;
        }
        context = null;
    }

    @Override public boolean isFreeModule() { return true; }
    @Override public Optional<String> getMountedResourceFolder() { return Optional.of("mounted"); }
    @Override public Optional<String> getMountPathAlias() { return Optional.of("apache-camel"); }

    private void registerCamelEntity() {
        Entity routes = groupedResourceEntity(
            CamelRouteResourceManager.META.getEntityDelegate()
                .getEntity(context, CamelRouteResourceManager.META).orElseThrow(),
            "Integrations", "/app/services/apache-camel/integrations"
        );
        context.getEntityManager().register(new EntityBuilder("Apache Camel")
            .description("Integrations with one Camel context and one or more YAML or Jython routes")
            .enablement(() -> true)
            .navigation("services", navigation -> navigation
                .includeInSectionOverview()
                .includeInDiagnosticOverview()
                .navAction("Configure Integrations", "/app/services/apache-camel/integrations"))
            .diagnostics(context, diagnostics -> diagnostics.healthcheck(
                "status",
                () -> com.codahale.metrics.health.HealthCheck.Result.healthy("RUNNING")
            ))
            .children(List.of(routes, groupedResourceEntity(CamelKameletManager.META.getEntityDelegate()
                .getEntity(context, CamelKameletManager.META).orElseThrow(), "Templates", "/app/services/apache-camel/library?tab=templates")))
            .build());
    }

    private Entity groupedResourceEntity(Entity resourceEntity, String name, String configureUrl) {
        EntityBuilder builder = new EntityBuilder(name)
            .enablement(() -> true)
            .navigation("services", navigation -> navigation
                .navAction("Configure " + name, configureUrl)
            );
        resourceEntity.getDescription().ifPresent(description -> builder.description(description.getDescription().orElse("")));
        resourceEntity.getDiagnostics().ifPresent(builder::diagnostics);
        resourceEntity.getChildren().ifPresent(children -> builder.children(
            children::getTotal,
            children::getChildren
        ));
        return builder.build();
    }
}
