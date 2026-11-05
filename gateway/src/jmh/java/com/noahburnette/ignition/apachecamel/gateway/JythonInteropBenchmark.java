package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.gateway.config.JsonResourceCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.openjdk.jmh.annotations.*;
import org.python.core.Py;
import org.python.core.PyDictionary;
import org.python.core.PyObject;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

/** Callback/conversion cost only: no Gateway lookup, I/O, route handoffs or script compilation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx512m", "-Dpython.import.site=false"})
@State(Scope.Thread)
public class JythonInteropBenchmark {
    @Param({"1", "100", "1000"})
    public int lineCount;

    private PythonInterpreter python;
    private PySystemState caller;
    private DefaultCamelContext context;
    private Exchange exchange;
    private Map<String, Object> payload;
    private final Map<String, Object> headers = Map.of("correlationId", "benchmark", "source", "jmh");
    private PyObject readNative;
    private PyObject readShallow;
    private PyObject nativeArgument;
    private PyObject transform;
    private Processor nativeProcessor;
    private Processor copyingProcessor;
    private Gson gson;

    @Setup(Level.Trial)
    public void setup() {
        caller = Py.getSystemState();
        python = new PythonInterpreter(null, new PySystemState());
        python.getSystemState().setClassLoader(JythonInteropBenchmark.class.getClassLoader());
        python.exec("""
            from java.util import ArrayList, LinkedHashMap, List, Map
            from org.apache.camel import Processor
            # Exact recursive helpers used by the original simulation.
            def py(value):
                if isinstance(value, Map):
                    return dict((unicode(e.getKey()), py(e.getValue())) for e in value.entrySet())
                if isinstance(value, List):
                    return [py(item) for item in value]
                return value
            def java(value):
                if isinstance(value, dict):
                    result = LinkedHashMap()
                    for key, item in value.items():
                        result.put(key, java(item))
                    return result
                if isinstance(value, (list, tuple)):
                    result = ArrayList()
                    for item in value:
                        result.add(java(item))
                    return result
                return value
            def read_native(payload):
                return payload['order']['quantity'] + len(payload['lines'])
            def read_shallow(payload):
                body = dict(payload)
                return body['order']['quantity'] + len(body['lines'])
            def transform(payload, headers):
                return dict(payload, validated=True)
            class Native(Processor):
                def process(self, exchange):
                    message = exchange.getMessage()
                    message.setBody(transform(message.getBody(), message.getHeaders()))
            class Copying(Processor):
                def process(self, exchange):
                    message = exchange.getMessage()
                    message.setBody(java(transform(py(message.getBody()), message.getHeaders())))
            native_processor = Native()
            copying_processor = Copying()
            """);
        readNative = python.get("read_native");
        readShallow = python.get("read_shallow");
        transform = python.get("transform");
        nativeProcessor = (Processor) python.get("native_processor").__tojava__(Processor.class);
        copyingProcessor = (Processor) python.get("copying_processor").__tojava__(Processor.class);
        payload = new LinkedHashMap<>();
        payload.put("order", new LinkedHashMap<>(Map.of("id", "WO-benchmark", "quantity", 9_007_199_254_740_993L)));
        var lines = new ArrayList<Map<String, Object>>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            var line = new LinkedHashMap<String, Object>();
            line.put("sku", "Part-" + i);
            line.put("quantity", i + 1);
            line.put("enabled", true);
            line.put("note", null);
            lines.add(line);
        }
        payload.put("lines", lines);
        payload.put("nothing", null);
        payload.put("description", "café 工場");
        nativeArgument = Py.java2py(payload);
        context = new DefaultCamelContext();
        exchange = new DefaultExchange(context);
        exchange.getMessage().setHeaders(new LinkedHashMap<>(headers));
        gson = JsonResourceCodec.buildGson(null);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        try {
            context.stop();
            python.close();
        } finally {
            Py.setSystemState(caller);
        }
    }

    @Benchmark
    public PyObject directJavaViewRead() {
        return readNative.__call__(nativeArgument);
    }

    @Benchmark
    public PyObject shallowDictRead() {
        return readShallow.__call__(nativeArgument);
    }

    @Benchmark
    public Object nativeProcessor() throws Exception {
        exchange.getMessage().setBody(payload);
        nativeProcessor.process(exchange);
        return exchange.getMessage().getBody();
    }

    @Benchmark
    public Object recursiveHelperProcessor() throws Exception {
        exchange.getMessage().setBody(payload);
        copyingProcessor.process(exchange);
        return exchange.getMessage().getBody();
    }

    @Benchmark
    public Object existingJsonBoundaryConversions() {
        // GatewayCamelServices.invoke conversion sequence and the same transform.
        // Deliberately excludes host ScriptManager lookup/compileFunction costs.
        var result = transform.__call__(TypeUtilities.gsonToPy(gson.toJsonTree(payload)),
            TypeUtilities.gsonToPy(gson.toJsonTree(headers)));
        return GatewayCamelServices.toJava(TypeUtilities.pyToGson(result, gson));
    }
}
