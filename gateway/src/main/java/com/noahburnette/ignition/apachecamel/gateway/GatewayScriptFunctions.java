package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.script.ScriptFunction;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.GatewaySystemProperties;

/** Compiles Camel project-script entry points using host-owned script managers. */
final class GatewayScriptFunctions {
    private GatewayScriptFunctions() {
    }

    static ScriptFunction route(GatewayContext context, String project, String functionPath) throws Exception {
        return compile(context, project,
            "def invoke(builder):\n"
                + "    _f = " + functionPath + "\n"
                + "    _c = getattr(_f, 'func_code', None)\n"
                + "    if _c is None or getattr(_f, 'im_self', None) is not None or _c.co_argcount != 1:\n"
                + "        raise TypeError('route function must be a plain project-script function accepting builder')\n"
                + "    return _f(builder)\n");
    }

    /**
     * Invokes a transform with the payload and a controlled metadata mapping.
     *
     * <p>Arity is inspected rather than discovered by catching {@code TypeError}:
     * a {@code TypeError} raised <em>inside</em> the transform would otherwise be
     * mistaken for a signature mismatch and silently retried with fewer
     * arguments, masking a real script bug.</p>
     */
    static ScriptFunction transform(GatewayContext context, String functionPath) throws Exception {
        return transform(context, null, functionPath);
    }

    static ScriptFunction transform(GatewayContext context, String project, String functionPath) throws Exception {
        return compile(
            context,
            project,
            "def invoke(payload, meta):\n"
                + "    _f = " + functionPath + "\n"
                + "    _c = getattr(_f, 'func_code', None)\n"
                + "    if _c is None or getattr(_f, 'im_self', None) is not None:\n"
                + "        raise TypeError('transform must be a plain project-script function')\n"
                + "    _n = _c.co_argcount\n"
                + "    if _n != 2:\n"
                + "        raise TypeError('transform must accept payload, meta')\n"
                + "    return _f(payload, meta)\n"
        );
    }

    static ScriptFunction compile(GatewayContext context, String source) throws Exception {
        return compile(context, null, source);
    }

    static ScriptFunction compile(GatewayContext context, String project, String source) throws Exception {
        String projectName = project == null ? context.getSystemPropertiesManager()
            .getPropertyValue(GatewaySystemProperties.GatewayScriptingProject) : project;
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalStateException("Gateway Scripting Project is not configured");
        }
        ScriptManager scriptManager =
            context.getProjectManager().getProjectScriptManager(projectName);
        if (scriptManager == null) {
            throw new IllegalStateException(
                "Gateway Scripting Project is unavailable: " + projectName
            );
        }
        return scriptManager.compileFunction(source);
    }
}
