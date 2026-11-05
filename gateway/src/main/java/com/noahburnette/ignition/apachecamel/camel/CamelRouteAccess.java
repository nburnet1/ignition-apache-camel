package com.noahburnette.ignition.apachecamel.camel;

import java.util.List;
import java.util.Map;

/** Named-context operations shared by Gateway lifecycle and scripting. */
public interface CamelRouteAccess {
    List<String> list();
    Map<String, Object> routeStatus(String name);
    void restart(String name);
    void send(String name, String endpoint, Object body, Map<String, Object> headers) throws Exception;
    Object request(String name, String endpoint, Object body, Map<String, Object> headers) throws Exception;
}
