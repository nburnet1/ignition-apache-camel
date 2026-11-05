package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.script.ScriptManager;
import org.python.core.Py;
import org.python.core.PyStringMap;
import org.python.core.PySystemState;

/** Adds Camel visibility once, while Ignition initializes an unpublished script manager. */
public final class CamelScriptClassLoading implements Runnable {
    private final ClassLoader module;
    public CamelScriptClassLoading() { this(CamelScriptClassLoading.class.getClassLoader()); }
    public CamelScriptClassLoading(ClassLoader module) { this.module = module; }
    static void initialize(ScriptManager manager) {
        initialize(manager, CamelScriptClassLoading.class.getClassLoader());
    }
    static void initialize(ScriptManager manager, ClassLoader loader) {
        PySystemState caller = Py.getSystemState();
        try {
            var locals = new PyStringMap();
            locals.__setitem__("_camelClassLoading", Py.java2py(new CamelScriptClassLoading(loader)));
            // runCode selects this manager's system state through the public SDK API.
            manager.runCode("_camelClassLoading.run()", locals, "<Camel class visibility>");
        } catch (Exception error) {
            throw new IllegalStateException("Could not expose Camel classes to project scripting", error);
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Override
    public void run() {
        var state = Py.getSystemState();
        ClassLoader existing = state.getClassLoader();
        if (existing == null) existing = org.python.core.imp.class.getClassLoader();
        state.setClassLoader(new ClassLoader(existing) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Leave Ignition and other modules' classes with the original loader.
                if (name.startsWith("org.apache.camel.") || com.noahburnette.ignition.apachecamel.camel.CamelExtensions.ownsClass(module, name)) return module.loadClass(name);
                try { return super.loadClass(name, resolve); }
                catch (ClassNotFoundException missing) { return module.loadClass(name); }
            }
        });
    }
}
