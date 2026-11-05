package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.noahburnette.ignition.apachecamel.camel.CamelRouteAccess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PyDictionary;
import org.python.core.PyList;

class CamelScriptModuleTest {
    @Test
    void restoresCallerPythonStateWhenDeliveryFails() {
        var caller = Py.getSystemState();
        var access = new Access();
        access.failure = new IllegalStateException("delivery failed");
        var api = new CamelScriptModule(access);
        try {
            assertThrows(IllegalStateException.class, () -> api.request(new PyObject[]{
                Py.newString("orders"), Py.newString("direct:test"), Py.None}, new String[0]));
            assertSame(caller, Py.getSystemState());
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void convertsJsonWithoutLosingIntegerPrecisionAndSupportsKeywordHeaders() throws Exception {
        var access = new Access();
        var api = new CamelScriptModule(access);
        var headers = new PyDictionary();
        headers.__setitem__(Py.newString("key"), Py.newLong(9007199254740993L));
        var result = api.request(new PyObject[]{Py.newString("orders"), Py.newString("direct:test"),
            Py.newLong(9007199254740993L), headers}, new String[]{"headers"});
        assertEquals(9007199254740993L, access.body);
        assertEquals(9007199254740993L, access.headers.get("key"));
        assertEquals(9007199254740993L, result.__tojava__(Long.class));
        headers.__setitem__(Py.newString("other"), Py.True);
        assertFalse(access.headers.containsKey("other"));
        api.send(new PyObject[]{Py.newString("orders"), Py.newString("direct:test"), Py.None}, new String[0]);
        assertNull(access.body);
        assertTrue(access.headers.isEmpty());
        assertSame(Py.None, api.request(new PyObject[]{Py.newString("orders"), Py.newString("direct:test"), Py.None}, new String[0]));
    }

    @Test
    void rejectsInvalidHeadersAndExposesLifecycleOperations() throws Exception {
        var access = new Access();
        var api = new CamelScriptModule(access);
        assertThrows(IllegalArgumentException.class, () -> api.send(new PyObject[]{Py.newString("orders"),
            Py.newString("direct:test"), Py.None, new PyList()}, new String[0]));
        assertEquals(List.of("orders"), api.list());
        assertEquals("RUNNING", api.status("orders").get("state"));
        api.restart("orders");
        assertTrue(access.restarted);
    }

    private static final class Access implements CamelRouteAccess {
        Object body;
        Map<String, Object> headers;
        boolean restarted;
        RuntimeException failure;
        public List<String> list() { return List.of("orders"); }
        public Map<String, Object> routeStatus(String name) { return Map.of("state", "RUNNING"); }
        public void restart(String name) { restarted = true; }
        public void send(String name, String endpoint, Object body, Map<String, Object> headers) {
            if (failure != null) {
                Py.setSystemState(new org.python.core.PySystemState());
                throw failure;
            }
            this.body = body;
            this.headers = headers;
        }
        public Object request(String name, String endpoint, Object body, Map<String, Object> headers) {
            send(name, endpoint, body, headers);
            return body;
        }
    }
}
