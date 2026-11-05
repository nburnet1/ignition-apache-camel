package com.noahburnette.ignition.apachecamel.gateway;

import java.util.LinkedHashMap;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.support.PropertyBindingSupport;
import org.apache.camel.support.jsse.SSLContextParameters;

/** Core-only MQTT 5 configuration; implementation and client classes stay extension-owned. */
final class CamelMqtt5 {
    private CamelMqtt5() { }

    static Component create(CamelContext context, CamelComponentResource.Mqtt config,
                            String password, SSLContextParameters tls) throws Exception {
        var component = CamelOptionalComponents.create(context, "paho-mqtt5");
        var settings = new LinkedHashMap<String, Object>();
        settings.put("brokerUrl", config.brokerUrl());
        if (config.username() != null) settings.put("userName", config.username());
        if (password != null) settings.put("password", password);
        settings.put("qos", config.qos());
        settings.put("cleanStart", config.cleanStart());
        settings.put("sessionExpiryInterval", config.sessionExpiryInterval());
        settings.put("persistence", "MEMORY");
        settings.put("connectionTimeout", config.connectionTimeout());
        settings.put("httpsHostnameVerificationEnabled", true);
        if (tls != null) settings.put("socketFactory", tls.createSSLContext(context).getSocketFactory());
        try {
            PropertyBindingSupport.build().withCamelContext(context).withTarget(component).withProperties(settings)
                .withMandatory(true).withNesting(false).withReference(false).bind();
            return component;
        } catch (Exception | LinkageError failure) {
            try { component.stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
