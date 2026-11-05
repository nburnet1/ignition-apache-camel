package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PyDictionary;
import org.python.core.PyException;
import org.python.core.PyList;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

/** Characterize the actual Jython/Camel boundary before adding any conversions. */
class JythonCamelInteropTest {
    private static final long LARGE_INTEGER = 9_007_199_254_740_993L;
    private static final BigInteger HUGE_INTEGER = BigInteger.ONE.shiftLeft(100).add(BigInteger.ONE);

    @Test
    void javaCollectionsSupportPythonOperationsWithoutRecursivelyCopying() {
        var original = payload();
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.set("payload", original);
            python.exec("""
                from java.util import Map, List
                assert isinstance(payload, Map)
                assert not isinstance(payload, dict)
                assert isinstance(payload['lines'], List)
                assert not isinstance(payload['lines'], list)
                assert payload['order']['quantity'] == 9007199254740993L
                assert payload['huge'] == 1267650600228229401496703205377L
                assert payload['nothing'] is None
                assert payload['enabled'] == True
                assert payload.get('missing', 'fallback') == 'fallback'
                assert 'order' in payload
                assert sorted(payload.keys()) == sorted(k for k, v in payload.items())
                payload['order']['quantity'] += 1
                payload['lines'].append({'sku': u'added', 'quantity': 2})
                payload.update({'status': 'inspected'})
                shallow = dict(payload, validated=True)
                assert shallow['validated'] is True
                assert len(payload['lines']) == 3
                order = payload['order']
                lines = payload['lines']
                """);
            assertSame(original, python.get("payload").__tojava__(Object.class));
            assertSame(original.get("order"), python.get("order").__tojava__(Object.class));
            assertSame(original.get("lines"), python.get("lines").__tojava__(Object.class));
            var shallow = (Map<?, ?>) python.get("shallow").__tojava__(Map.class);
            assertSame(original.get("order"), shallow.get("order"));
            assertEquals(BigInteger.valueOf(LARGE_INTEGER + 1), ((Map<?, ?>) original.get("order")).get("quantity"),
                "Jython long arithmetic returns a BigInteger through the native Java view");
            assertEquals("inspected", original.get("status"));
            assertEquals(3, ((List<?>) original.get("lines")).size());
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void pythonContainersAlreadyImplementJavaMapAndList() {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.exec("""
                payload = {'text': u'caf\\u00e9 \\u5de5\\u5834', 'enabled': True, 'nothing': None,
                           'integer': 9007199254740993L, 'huge': 1267650600228229401496703205377L,
                           'nested': {'lines': [{'quantity': 7}, None]}}
                """);
            var value = python.get("payload");
            assertInstanceOf(PyDictionary.class, value);
            var mapping = assertInstanceOf(Map.class, value.__tojava__(Object.class));
            assertEquals("café 工場", mapping.get("text"));
            assertEquals(Boolean.TRUE, mapping.get("enabled"));
            assertNull(mapping.get("nothing"));
            assertEquals(BigInteger.valueOf(LARGE_INTEGER), mapping.get("integer"));
            assertEquals(HUGE_INTEGER, mapping.get("huge"));
            var nested = assertInstanceOf(Map.class, mapping.get("nested"));
            var lines = assertInstanceOf(List.class, nested.get("lines"));
            assertInstanceOf(PyList.class, lines);
            assertEquals(7, ((Map<?, ?>) lines.get(0)).get("quantity"));
            assertNull(lines.get(1));
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void directJythonProcessorCanUseNativeJavaContainersAndKeepTheirIdentity() throws Exception {
        var original = payload();
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.exec("""
                from org.apache.camel import Processor
                class Inspect(Processor):
                    def process(self, exchange):
                        body = exchange.getMessage().getBody()
                        body.update({'validated': True})
                        exchange.getMessage().setBody(body)
                def configure(builder):
                    builder.from_('direct:inspect').process(Inspect()).setHeader('quantity', builder.simple('${body[order][quantity]}')).setBody(builder.simple('${body[lines]}'))
                """);
            try (var runtime = runtime(python)) {
                assertSame(original.get("lines"), runtime.request("direct:inspect", original));
            }
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void returningAPythonDictWorksWithoutSimpleButNestedSimpleBeanCachingRejectsItsHashCode() throws Exception {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.exec("""
                from org.apache.camel import Processor
                class Inspect(Processor):
                    def process(self, exchange):
                        exchange.getMessage().setBody(dict(exchange.getMessage().getBody(), validated=True))
                def configure(builder):
                    builder.from_('direct:native').process(Inspect())
                    builder.from_('direct:simple').process(Inspect()).setBody(builder.simple('${body[order][quantity]}'))
                    builder.from_('direct:flat').process(Inspect()).setBody(builder.simple('${body[enabled]}'))
                """);
            try (var runtime = runtime(python)) {
                var result = assertInstanceOf(PyDictionary.class, runtime.request("direct:native", payload()));
                assertEquals(Boolean.TRUE, ((Map<?, ?>) result).get("validated"));
                var failure = assertThrows(Exception.class, () -> runtime.request("direct:simple", payload()));
                assertTrue(rootCause(failure).toString().contains("unhashable type: 'dict'"), failure.toString());
                var flatFailure = assertThrows(Exception.class, () -> runtime.request("direct:flat", payload()));
                assertTrue(rootCause(flatFailure).toString().contains("unhashable type: 'dict'"), flatFailure.toString());
                assertThrows(PyException.class, result::hashCode);
            }
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void shallowJavaOuterMapDoesNotFixUnhashablePythonChildrenForCamelSimple() throws Exception {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.exec("""
                from java.util import LinkedHashMap
                from org.apache.camel import Processor
                class Mixed(Processor):
                    def process(self, exchange):
                        result = LinkedHashMap()
                        result.put('enabled', True)
                        result.put('order', {'quantity': 7})
                        exchange.getMessage().setBody(result)
                def configure(builder):
                    builder.from_('direct:mixed').process(Mixed()).setBody(builder.simple('${body[enabled]}'))
                """);
            try (var runtime = runtime(python)) {
                var failure = assertThrows(Exception.class, () -> runtime.request("direct:mixed", payload()));
                assertTrue(rootCause(failure).toString().contains("unhashable type: 'dict'"), failure.toString());
            }
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void camelSplitterCanConsumeAPythonListWithoutAnArrayListCopy() throws Exception {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.exec("""
                from org.apache.camel import Processor
                from org.apache.camel.processor.aggregate import GroupedBodyAggregationStrategy
                lines = [{'quantity': 7}, {'quantity': 8}]
                class Keep(Processor):
                    def process(self, exchange):
                        pass
                def configure(builder):
                    builder.from_('direct:split').split(builder.body(), GroupedBodyAggregationStrategy()).process(Keep()).end()
                """);
            try (var runtime = runtime(python)) {
                var result = assertInstanceOf(List.class, runtime.request("direct:split", python.get("lines")));
                assertEquals(2, result.size());
                assertEquals(7, ((Map<?, ?>) result.get(0)).get("quantity"));
                assertEquals(8, ((Map<?, ?>) result.get(1)).get("quantity"));
            }
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void nativePythonAndMixedContainersCanBeMarshalledByRealCamelJackson() throws Exception {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.set("java_payload", payload());
            python.exec("""
                from org.apache.camel.model.dataformat import JsonLibrary
                native = {'integer': 9007199254740993L, 'huge': 1267650600228229401496703205377L,
                          'text': u'caf\\u00e9 \\u5de5\\u5834', 'nothing': None, 'enabled': True,
                          'lines': [{'quantity': 7}, None]}
                mixed = dict(java_payload, python_child={'values': [True, None, 9007199254740993L]})
                def configure(builder):
                    builder.from_('direct:json').marshal().json(JsonLibrary.Jackson).unmarshal().json(JsonLibrary.Jackson)
                """);
            try (var runtime = runtime(python)) {
                var result = assertInstanceOf(Map.class, runtime.request("direct:json", python.get("native")));
                assertEquals(LARGE_INTEGER, result.get("integer"));
                assertEquals(HUGE_INTEGER, result.get("huge"));
                assertEquals("café 工場", result.get("text"));
                assertNull(result.get("nothing"));
                assertEquals(Boolean.TRUE, result.get("enabled"));
                var lines = assertInstanceOf(List.class, result.get("lines"));
                assertEquals(7, ((Map<?, ?>) lines.get(0)).get("quantity"));
                assertNull(lines.get(1));
                var mixed = assertInstanceOf(Map.class, runtime.request("direct:json", python.get("mixed")));
                assertEquals(LARGE_INTEGER, ((Map<?, ?>) mixed.get("order")).get("quantity"));
                assertEquals(List.of(true, LARGE_INTEGER), withoutNulls((List<?>) ((Map<?, ?>) mixed.get("python_child")).get("values")));
            }
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void existingIgnitionJsonBoundaryCopiesTheTreeButDoesNotPreserveArbitraryPrecisionIntegers() {
        var original = payload();
        var gson = JsonResourceCodec.buildGson(null);
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            // Same conversions used by GatewayCamelServices.invoke, without a Gateway.
            var argument = TypeUtilities.gsonToPy(gson.toJsonTree(original));
            assertInstanceOf(PyDictionary.class, argument);
            assertInstanceOf(org.python.core.PyFloat.class, argument.__getitem__(Py.newString("huge")),
                "The >Int64 integer becomes a float before the project function is called");
            python.set("payload", argument);
            python.exec("""
                assert isinstance(payload, dict)
                assert isinstance(payload['lines'], list)
                def transform(body, headers):
                    body['order']['quantity'] += 1
                    headers['injected'] = True
                    return dict(body, validated=True)
                """);
            var headers = new LinkedHashMap<>(Map.of("source", "original"));
            var result = python.get("transform").__call__(argument, TypeUtilities.gsonToPy(gson.toJsonTree(headers)));
            var converted = assertInstanceOf(Map.class, GatewayCamelServices.toJava(TypeUtilities.pyToGson(result, gson)));
            assertNotSame(original, converted);
            assertNotSame(original.get("order"), converted.get("order"));
            assertNotSame(original.get("lines"), converted.get("lines"));
            assertEquals(LARGE_INTEGER + 1, ((Map<?, ?>) converted.get("order")).get("quantity"));
            assertEquals(LARGE_INTEGER, ((Map<?, ?>) original.get("order")).get("quantity"));
            assertFalse(headers.containsKey("injected"), "The existing JSON boundary keeps headers defensive");
            assertNotEquals(HUGE_INTEGER, converted.get("huge"),
                "The SDK JSON conversion is not an arbitrary-precision integer identity boundary");
            assertNull(converted.get("nothing"));
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void nativeAndSdkBoundariesRetainSignedInt64LimitsButOnlyNativeRetainsLargerIntegers() {
        var gson = JsonResourceCodec.buildGson(null);
        for (long value : new long[]{Long.MIN_VALUE, -LARGE_INTEGER, 0, LARGE_INTEGER, Long.MAX_VALUE}) {
            var nativeValue = Py.java2py(value);
            assertEquals(BigInteger.valueOf(value), nativeValue.__tojava__(BigInteger.class));
            var jsonValue = TypeUtilities.gsonToPy(gson.toJsonTree(value));
            assertEquals(value, GatewayCamelServices.toJava(TypeUtilities.pyToGson(jsonValue, gson)));
        }
        var nativeHuge = Py.java2py(HUGE_INTEGER);
        assertEquals(HUGE_INTEGER, nativeHuge.__tojava__(BigInteger.class));
        var copiedHuge = TypeUtilities.gsonToPy(gson.toJsonTree(HUGE_INTEGER));
        assertInstanceOf(org.python.core.PyFloat.class, copiedHuge);
        assertNotEquals(HUGE_INTEGER, GatewayCamelServices.toJava(TypeUtilities.pyToGson(copiedHuge, gson)));
    }

    @Test
    void nativeAccessPreservesNonStringKeysAndSupportsCyclesWithoutTraversal() {
        var keyed = new LinkedHashMap<Object, Object>();
        var sentinel = new Object();
        keyed.put(7, sentinel);
        keyed.put("7", "text key");
        keyed.put("self", keyed);
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.set("payload", keyed);
            python.exec("""
                assert payload[7] != payload['7']
                value = payload[7]
                cycle = payload['self']
                """);
            assertSame(sentinel, python.get("value").__tojava__(Object.class));
            assertSame(keyed, python.get("cycle").__tojava__(Object.class));
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void nativeJavaViewPreservesReadOnlyCollectionSemanticsInsteadOfHidingThemWithACopy() {
        var original = Map.of("quantity", 7);
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            python.set("payload", original);
            python.exec("assert payload['quantity'] == 7");
            var failure = assertThrows(Exception.class, () -> python.exec("payload['quantity'] = 8"));
            assertInstanceOf(UnsupportedOperationException.class, rootCause(failure));
            assertSame(original, python.get("payload").__tojava__(Object.class));
            assertEquals(7, original.get("quantity"));
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Test
    void helpersAreCopiesNotRequiredAdaptersAndChangeJavaMappingSemantics() throws Exception {
        var caller = Py.getSystemState();
        try (var python = interpreter()) {
            var source = java.nio.file.Files.readString(java.nio.file.Path.of(
                System.getProperty("camel.examplesDirectory"), "simulation", "eip_simulation.py"));
            python.exec("from java.util import ArrayList, LinkedHashMap, List, Map\n"
                + source.substring(source.indexOf("def py("), source.indexOf("def jmsFactory(")));
            var original = payload();
            python.set("payload", original);
            python.exec("converted = java(py(payload))");
            var copy = assertInstanceOf(Map.class, python.get("converted").__tojava__(Object.class));
            assertEquals(BigInteger.valueOf(LARGE_INTEGER), ((Map<?, ?>) copy.get("order")).get("quantity"));
            assertEquals(HUGE_INTEGER, copy.get("huge"));
            assertEquals(true, copy.get("enabled"));
            assertNull(copy.get("nothing"));
            assertNotSame(original, copy);
            assertNotSame(original.get("order"), copy.get("order"));
            assertNotSame(original.get("lines"), copy.get("lines"));
            var keys = new LinkedHashMap<Object, Object>();
            keys.put(7, "integer-key-value");
            keys.put("7", "string-key-value");
            python.set("keys", keys);
            python.exec("changed_keys = py(keys)");
            assertEquals(1, ((Map<?, ?>) python.get("changed_keys").__tojava__(Map.class)).size(),
                "py() stringifies keys and silently collapses 7 and '7'");
        } finally {
            Py.setSystemState(caller);
        }
    }

    private static List<?> withoutNulls(List<?> input) {
        return input.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static PythonInterpreter interpreter() {
        var python = new PythonInterpreter(null, new PySystemState());
        python.getSystemState().setClassLoader(JythonCamelInteropTest.class.getClassLoader());
        return python;
    }

    private static CamelRouteRuntime runtime(PythonInterpreter python) throws Exception {
        return new CamelRouteRuntime("interop", new CamelRouteResource("JYTHON", null, "test", "routes.configure"),
            context -> { }, builder -> python.get("configure").__call__(Py.java2py(builder)));
    }

    private static Map<String, Object> payload() {
        var result = new LinkedHashMap<String, Object>();
        result.put("order", new LinkedHashMap<>(Map.of("quantity", LARGE_INTEGER, "id", "WO-interop")));
        result.put("lines", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("quantity", 7)), new LinkedHashMap<>(Map.of("quantity", 8)))));
        result.put("huge", HUGE_INTEGER);
        result.put("nothing", null);
        result.put("enabled", true);
        return result;
    }
}
