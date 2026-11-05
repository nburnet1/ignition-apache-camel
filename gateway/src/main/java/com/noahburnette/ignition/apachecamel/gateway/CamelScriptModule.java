package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.script.PyArgParser;
import com.inductiveautomation.ignition.common.script.builtin.KeywordArgs;
import com.inductiveautomation.ignition.common.script.hints.JythonElement;
import com.inductiveautomation.ignition.common.script.hints.ScriptArg;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import java.util.Map;
import java.util.List;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteAccess;
import org.python.core.Py;
import org.python.core.PyObject;

/** Gateway-scoped scripting access to named, isolated Camel contexts. */
public final class CamelScriptModule {
    private static final Gson GSON = JsonResourceCodec.buildGson(null);
    static {
        BundleUtil.get().addBundle("CamelScriptModule", CamelScriptModule.class.getClassLoader(),
            CamelScriptModule.class.getName().replace('.', '/'));
    }
    private final CamelRouteAccess routes;

    public CamelScriptModule(CamelRouteAccess routes) { this.routes = routes; }

    @JythonElement(docBundlePrefix = "CamelScriptModule")
    public List<String> list() { return routes.list(); }

    @JythonElement(docBundlePrefix = "CamelScriptModule")
    public Map<String, Object> status(@ScriptArg("name") String name) { return routes.routeStatus(name); }

    @JythonElement(docBundlePrefix = "CamelScriptModule")
    public void restart(@ScriptArg("name") String name) { routes.restart(name); }

    @KeywordArgs(names = {"name", "endpoint", "payload", "headers"},
        types = {String.class, String.class, PyObject.class, PyObject.class})
    @JythonElement(docBundlePrefix = "CamelScriptModule")
    public void send(PyObject[] args, String[] keywords) throws Exception {
        var input = input(args, keywords, "send");
        var caller = Py.getSystemState();
        try {
            routes.send(input.name(), input.endpoint(), input.body(), input.headers());
        } finally {
            Py.setSystemState(caller);
        }
    }

    @KeywordArgs(names = {"name", "endpoint", "payload", "headers"},
        types = {String.class, String.class, PyObject.class, PyObject.class})
    @JythonElement(docBundlePrefix = "CamelScriptModule")
    public PyObject request(PyObject[] args, String[] keywords) throws Exception {
        var input = input(args, keywords, "request");
        var caller = Py.getSystemState();
        try {
            Object result = routes.request(input.name(), input.endpoint(), input.body(), input.headers());
            return result == null ? Py.None : TypeUtilities.gsonToPy(GSON.toJsonTree(result));
        } finally {
            Py.setSystemState(caller);
        }
    }

    private static Input input(PyObject[] args, String[] keywords, String function) {
        var parser = PyArgParser.parseArgs(args, keywords,
            new String[]{"name", "endpoint", "payload", "headers"},
            new Class<?>[]{String.class, String.class, PyObject.class, PyObject.class}, function);
        String name = parser.requireString("name");
        String endpoint = parser.requireString("endpoint");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("endpoint is required");
        Object body = GatewayCamelServices.toJava(TypeUtilities.pyToGson(parser.requirePyObject("payload"), GSON));
        Map<String, Object> headers = new java.util.LinkedHashMap<>();
        var headerValue = parser.getPyObject("headers").orElse(Py.None);
        if (headerValue != Py.None) {
            var json = TypeUtilities.pyToGson(headerValue, GSON);
            if (!json.isJsonObject()) throw new IllegalArgumentException("headers must be a mapping or None");
            json.getAsJsonObject().entrySet().forEach(entry ->
                headers.put(entry.getKey(), GatewayCamelServices.toJava(entry.getValue())));
        }
        return new Input(name, endpoint, body, headers);
    }

    private record Input(String name, String endpoint, Object body, Map<String, Object> headers) { }
}
