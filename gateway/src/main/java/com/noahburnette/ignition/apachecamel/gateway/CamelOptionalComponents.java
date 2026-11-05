package com.noahburnette.ignition.apachecamel.gateway;

import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.support.PluginHelper;

/** Public core-only boundary; component-specific adapter classes live in the extension loader. */
public final class CamelOptionalComponents {
    private CamelOptionalComponents() { }
    public static Component create(CamelContext context, String scheme) throws Exception {
        Component component;
        if (scheme.equals("http") || scheme.equals("https")) {
            var type = context.getApplicationContextClassLoader().loadClass(
                "com.noahburnette.ignition.apachecamel.gateway.optional.NamedHttpComponent");
            component = (Component) type.getConstructor(String.class).newInstance(scheme);
        } else component = PluginHelper.getComponentResolver(context).resolveComponent(scheme, context);
        if (component == null) throw new IllegalStateException("Required component library is not installed: " + scheme);
        component.setCamelContext(context);
        return component;
    }
}
