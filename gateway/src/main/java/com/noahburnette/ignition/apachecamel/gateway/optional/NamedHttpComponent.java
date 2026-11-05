package com.noahburnette.ignition.apachecamel.gateway.optional;

import java.util.Map;
import org.apache.camel.Endpoint;
import org.apache.camel.component.http.HttpComponent;

/** Loaded alongside the managed HTTP JAR, never linked from the host's class loader. */
public final class NamedHttpComponent extends HttpComponent {
    private final String transport;
    public NamedHttpComponent(String transport) { this.transport = transport; }
    @Override protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        return super.createEndpoint(transport + uri.substring(uri.indexOf(':')), remaining, parameters);
    }
}
