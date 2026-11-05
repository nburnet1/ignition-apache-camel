"""The running factory. All routing here is native Camel's Java DSL."""
from org.apache.camel.component.jms import JmsComponent
from org.apache.camel.model.dataformat import JsonLibrary


def installJms(builder):
    factory = builder.getContext().getRegistry().lookupByName("eip-jms-factory")
    if factory is None:
        raise Exception("Select the eip-jms-factory registry entry")
    builder.getContext().addComponent("jms", JmsComponent.jmsComponentTransacted(factory))
    return factory


def writes(builder, route):
    return route.split(builder.body()).setHeader("eipTag", builder.simple("${body[path]}"))\
        .setBody(builder.simple("${body[value]}")).toD("ignition-tag:${header.eipTag}").end()


def configure(builder):
    installJms(builder)
    builder.errorHandler(builder.deadLetterChannel("jms:queue:eip.workorders.dead?deliveryPersistent=true")
                         .maximumRedeliveries(2).redeliveryDelay(250).useOriginalMessage())
    script = "ignition-script:eip_simulation.%s?project=global"
    builder.from_("timer:work-orders?period=30000&delay=1000&fixedRate=true").routeId("wo-generate-every-30-seconds")\
        .to(script % "generate").marshal().json(JsonLibrary.Jackson)\
        .to("jms:queue:eip.workorders.new?deliveryPersistent=true&jmsMessageType=Text")
    builder.from_("jms:queue:eip.workorders.new?transacted=true").routeId("wo-persist-documents")\
        .to(script % "accept").to("direct:project").to("direct:notify")
    builder.from_("ignition-gateway-http:/eip-work-orders/start").routeId("wo-start-http")\
        .to("ignition-message:startWorkOrder?project=global&mode=request")
    builder.from_("direct:ui-start").routeId("wo-start-perspective")\
        .to("ignition-message:startWorkOrder?project=global&mode=request")
    builder.from_("direct:ui-complete").routeId("wo-complete-perspective")\
        .to("ignition-message:completeWorkOrder?project=global&mode=request")
    builder.from_("ignition-gateway-http:/eip-work-orders/complete").routeId("wo-complete-http")\
        .to("ignition-message:completeWorkOrder?project=global&mode=request")
    builder.from_("ignition-gateway-http:/eip-work-orders/snapshot").routeId("wo-snapshot-http").to(script % "snapshot")
    builder.from_("direct:start").routeId("wo-start-command").to(script % "start")\
        .wireTap("direct:project").wireTap("direct:notify")
    builder.from_("direct:complete").routeId("wo-complete-command").to(script % "complete")\
        .wireTap("direct:project").wireTap("direct:notify")
    builder.from_("timer:machine-cycle?period=1000&delay=2000").routeId("wo-machine-cycle")\
        .to(script % "advance").to("direct:writes")
    builder.from_("direct:project").routeId("wo-project-dashboard").to(script % "projectTags").to("direct:writes")
    writes(builder, builder.from_("direct:writes").routeId("wo-native-tag-writes"))
    builder.from_("ignition-tag:[default]EIPSimulation/Machines/*/Complete?includeInitial=false")\
        .routeId("wo-native-completion-signals").filter(builder.simple("${body} == true"))\
        .to(script % "completionSignal").to("ignition-message:completeWorkOrder?project=global&mode=request").end()
    builder.from_("direct:notify").routeId("wo-notify-real-perspective-sessions").to(script % "subscriptions")\
        .split(builder.body()).setHeader("eipSession", builder.simple("${body[session]}"))\
        .setBody(builder.simple("${body[message]}"))\
        .toD("ignition-perspective-message:workOrdersChanged?sessionId=${header.eipSession}&scope=session").end()
    builder.from_("ignition-gateway-http:/eip-work-orders/tag-probe").routeId("wo-tag-probe").to(script % "tagProbe").to("direct:writes")
    builder.from_("ignition-tag:[default]EIPSimulation/Tests/TagProbe?includeInitial=false")\
        .routeId("wo-observe-native-probe").to(script % "observeProbe").to("direct:writes")
    builder.from_("ignition-gateway-http:/eip-work-orders/coverage").routeId("wo-coverage-http").to(script % "coverage")
    builder.from_("ignition-gateway-http:/eip-work-orders/tag-inventory").routeId("wo-tag-inventory").to(script % "tagInventory")


def mqtt(builder):
    # Every atomic tag in default, including initial values and tags added later.
    # No subscription to broker output and no mirror tags: no feedback loop.
    builder.from_("ignition-tag:[default]**?includeInitial=true&capacity=20000")\
        .routeId("all-default-tags-to-mqtt").to("ignition-script:eip_simulation.mqttFrame?project=global")\
        .setHeader("CamelPahoOverrideTopic", builder.simple("${body[topic]}"))\
        .setBody(builder.simple("${body[message]}"))\
        .to("simulation-mqtt:ignition/default?retained=true&qos=1")
