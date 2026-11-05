package com.noahburnette.ignition.apachecamel.camel;

import java.util.concurrent.Callable;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.python.core.Py;
import org.python.core.PySystemState;

/** Jython authors the real Camel model; there is no parallel Python routing engine. */
public final class JythonRoutes {
    private JythonRoutes() { }

    /** Java callers must not inherit the system state selected by a Python Processor proxy. */
    static <T> T preservingCallerState(Callable<T> action) throws Exception {
        PySystemState previous = Py.getSystemState();
        try {
            return action.call();
        } finally {
            Py.setSystemState(previous);
        }
    }

    @FunctionalInterface
    public interface Configurer {
        void configure(Builder builder) throws Exception;
    }

    static void load(DefaultCamelContext context, Configurer function) throws Exception {
        if (function == null) throw new IllegalArgumentException("A project-library function resolver is required");
        context.addRoutes(new Builder() {
            @Override
            public void configure() throws Exception {
                function.configure(this);
            }
        });
    }

    /** Python cannot call a method spelled 'from' directly. All other DSL calls are native Camel. */
    public abstract static class Builder extends RouteBuilder {
        public RouteDefinition from_(String uri) {
            return from(uri);
        }
    }
}
