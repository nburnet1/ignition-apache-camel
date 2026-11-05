package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.camel.Processor;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

class CamelScriptClassLoadingTest {
    @Test
    void exposesCamelWithoutReplacingHostClassResolution() throws Exception {
        var caller = Py.getSystemState();
        var state = new PySystemState();
        var host = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.apache.camel.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        state.setClassLoader(host);
        try (var interpreter = new PythonInterpreter(null, state)) {
            Py.setSystemState(state);
            assertThrows(ClassNotFoundException.class, () -> host.loadClass(Processor.class.getName()));
            new CamelScriptClassLoading().run();
            assertSame(Processor.class, state.getClassLoader().loadClass(Processor.class.getName()));
            assertSame(String.class, state.getClassLoader().loadClass("java.lang.String"));
            interpreter.exec("from org.apache.camel import Processor\nclass Callback(Processor):\n    def process(self, exchange):\n        pass\ncallback = Callback()\n");
            assertInstanceOf(Processor.class, interpreter.get("callback").__tojava__(Processor.class));
        } finally {
            Py.setSystemState(caller);
        }
    }
}
