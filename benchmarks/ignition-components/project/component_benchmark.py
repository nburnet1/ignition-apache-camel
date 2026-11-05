"""Real-Gateway measurements. No SDK stubs and no changes to simulation converters."""
from java.lang import System, Long
from java.lang import Exception as JavaException
from java.util import LinkedHashMap, ArrayList
from java.util.concurrent import ConcurrentHashMap, CountDownLatch, TimeUnit
from java.util.concurrent.atomic import AtomicInteger, AtomicLong
from org.apache.camel import Processor, ExchangePattern
import math

RESOURCE = "camel-component-benchmark"
ROOT = "[default]EIPComponentBenchmark"
PROJECT = "global"


def obj(**values):
    """Explicit control/result DTO, not a recursive payload converter."""
    result = LinkedHashMap()
    for key, value in values.items():
        result.put(key, value)
    return result


def echo(payload, headers):
    return payload


def setup(payload, headers):
    def ensure(parent, definition):
        path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
        if not system.tag.exists(path):
            assert all(q.isGood() for q in system.tag.configure(parent, [definition], "a")), path
        return path
    ensure("[default]", {"name": "EIPComponentBenchmark", "tagType": "Folder"})
    parent = ensure(ROOT, {"name": "Glob", "tagType": "Folder"})
    leaf = ensure(parent, {"name": "Machine", "tagType": "Folder"})
    for parent, name in [(ROOT, "WriteOnly"), (ROOT, "Exact"), (leaf, "Value")]:
        ensure(parent, {"name": name, "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int8", "value": 0})
    return {"ready": True}


class Fn(Processor):
    def __init__(self, function):
        self.function = function
    def process(self, exchange):
        self.function(exchange)


class Signal(object):
    def __init__(self, paddingBytes):
        self.latch = CountDownLatch(1)
        self.received = 0
        self.paddingBytes = paddingBytes
    def arrive(self):
        self.received = System.nanoTime()
        self.latch.countDown()


class Probe(Processor):
    def __init__(self):
        self.pending = ConcurrentHashMap()
        self.unexpected = AtomicInteger()
        self.sequence = AtomicLong()
        self.busy = AtomicInteger()
        self.report = obj(state="IDLE", rows=ArrayList())
        self.samples = []
        self.phase = "IDLE"
        self.runId = ""

    def receipt(self, kind, exchange):
        body = exchange.getMessage().getBody()
        sequence = body.get("sequence") if kind in ["message", "perspective"] else body
        signal = self.pending.remove(kind + ":" + unicode(sequence))
        if signal is None:
            self.unexpected.incrementAndGet()
        else:
            if kind in ["message", "perspective"]:
                assert len(body.get("padding")) == signal.paddingBytes, "Delivery payload was truncated"
            else:
                assert exchange.getMessage().getHeader("ignitionTagGood") == True, "Bad native tag quality"
            signal.arrive()

    def snapshot(self, exchange):
        if self.busy.get():
            exchange.getMessage().setBody(obj(state="RUNNING", phase=self.phase, runId=self.runId))
        else:
            exchange.getMessage().setBody(self.report)

    def samplePage(self, exchange):
        assert not self.busy.get(), "Wait for the run to finish before exporting samples"
        index = int(exchange.getMessage().getBody().get("index"))
        assert 0 <= index < len(self.samples)
        exchange.getMessage().setBody(obj(nanos=ArrayList(self.samples[index])))

    def process(self, exchange):
        config = exchange.getMessage().getBody()
        count, warmup = int(config.get("samples")), int(config.get("warmup"))
        assert 20 <= count <= 5000 and 1 <= warmup <= 1000
        sizes = [int(value) for value in config.get("paddingBytes")]
        assert 1 <= len(sizes) <= 4 and all(0 <= value <= 32768 for value in sizes)
        session = unicode(config.get("sessionId") or "")
        assert self.busy.compareAndSet(0, 1), "A benchmark is already running"
        self.runId = unicode(config.get("runId"))
        producer = exchange.getContext().createProducerTemplate()
        rows = ArrayList()
        self.samples = []
        self.unexpected.set(0)
        try:
            self.case(producer, rows, "direct-baseline", "direct:baseline", count, warmup, 0)
            self.case(producer, rows, "ignition-tag.write", "direct:tag-write", count, warmup, 0, scalar=True)
            self.case(producer, rows, "ignition-tag.exact.write-to-callback", "direct:tag-exact", count, warmup, 0, signalKind="exact", scalar=True)
            self.case(producer, rows, "ignition-tag.glob.write-to-callback", "direct:tag-glob", count, warmup, 0, signalKind="glob", scalar=True)
            for size in sizes:
                self.case(producer, rows, "ignition-script.echo", "direct:script", count, warmup, size)
                self.case(producer, rows, "ignition-message.request-reply", "direct:message-request", count, warmup, size)
                self.case(producer, rows, "ignition-message.send", "direct:message-send", count, warmup, size, signalKind="message")
                if session:
                    self.case(producer, rows, "ignition-perspective-message.session", "direct:perspective", count, warmup, size, signalKind="perspective", session=session)
            assert not self.pending.size(), "Delivery remained outstanding"
            assert self.unexpected.get() == 0, "Duplicate/unmatched delivery callbacks"
            self.report = obj(state="COMPLETE", rows=rows, unexpectedCallbacks=self.unexpected.get(), runId=self.runId,
                samplesPerCase=count, warmupPerCase=warmup,
                perspective="measured" if session else "NOT MEASURED: no live benchmark page session supplied")
        except (Exception, JavaException) as error:
            self.report = obj(state="FAILED", phase=self.phase, error=unicode(error), rows=rows, runId=self.runId,
                pending=self.pending.size(), unexpectedCallbacks=self.unexpected.get())
        finally:
            producer.stop()
            self.busy.set(0)

    def case(self, producer, rows, name, endpoint, count, warmup, size, signalKind=None, scalar=False, session=""):
        self.phase = name + "/" + str(size)
        body = obj(sequence=0, padding="x" * size)
        headers = obj(benchmarkSession=session)
        durations, delivery = [], []
        startedMeasured = 0
        for index in range(warmup + count):
            sequence = self.sequence.incrementAndGet()
            body.put("sequence", Long(sequence))
            signal = Signal(size) if signalKind else None
            if signal:
                self.pending.put(signalKind + ":" + unicode(sequence), signal)
            value = Long(sequence) if scalar else body
            if index == warmup:
                startedMeasured = System.nanoTime()
            started = System.nanoTime()
            result = producer.requestBodyAndHeaders(endpoint, value, headers)
            returned = System.nanoTime()
            if signal:
                assert getattr(signal.latch, "await")(10, TimeUnit.SECONDS), "No correlated native delivery: " + self.phase
            elif scalar:
                assert long(result) == sequence
            else:
                assert long(result.get("sequence")) == sequence
                assert len(result.get("padding")) == size
            if index >= warmup:
                durations.append(returned - started)
                if signal:
                    delivery.append(signal.received - started)
        elapsed = System.nanoTime() - startedMeasured
        self.addRow(rows, name + (".dispatch-return" if signalKind in ["message", "perspective"] else ".return"), size, durations, elapsed)
        if signalKind:
            self.addRow(rows, name + ".delivery", size, delivery, elapsed)

    def addRow(self, rows, name, size, values, elapsed):
        ordered = sorted(values)
        def percentile(fraction):
            return ordered[max(0, int(math.ceil(len(ordered) * fraction)) - 1)] / 1000.0
        rows.add(obj(name=name, paddingBytes=size, samples=len(values), meanMicros=sum(values) / float(len(values)) / 1000,
            p50Micros=percentile(.50), p95Micros=percentile(.95), p99Micros=percentile(.99), maxMicros=ordered[-1] / 1000.0,
            closedLoopOpsPerSecond=len(values) * 1000000000.0 / elapsed, sampleIndex=len(self.samples), errors=0))
        self.samples.append(values)


def configure(builder):
    probe = Probe()
    builder.from_("direct:baseline").routeId("bench-baseline").process(Fn(lambda e: None))
    builder.from_("direct:tag-write").routeId("bench-tag-write").to("ignition-tag:" + ROOT + "/WriteOnly")
    builder.from_("direct:tag-exact").routeId("bench-tag-exact-write").to("ignition-tag:" + ROOT + "/Exact")
    builder.from_("direct:tag-glob").routeId("bench-tag-glob-write").to("ignition-tag:" + ROOT + "/Glob/Machine/Value")
    builder.from_("ignition-tag:" + ROOT + "/Exact?includeInitial=false&capacity=1024").routeId("bench-exact-observer").process(Fn(lambda e: probe.receipt("exact", e)))
    builder.from_("ignition-tag:" + ROOT + "/Glob/*/Value?includeInitial=false&capacity=1024").routeId("bench-glob-observer").process(Fn(lambda e: probe.receipt("glob", e)))
    builder.from_("direct:script").routeId("bench-script").to("ignition-script:component_benchmark.echo?project=global")
    builder.from_("direct:message-request").routeId("bench-native-request").to("ignition-message:camelComponentBenchmarkEcho?project=global&mode=request")
    builder.from_("direct:message-send").routeId("bench-native-send").to("ignition-message:camelComponentBenchmarkDelivered?project=global&mode=send")
    builder.from_("direct:message-delivered").routeId("bench-native-handler-receipt").process(Fn(lambda e: probe.receipt("message", e)))
    builder.from_("direct:perspective").routeId("bench-perspective-dispatch").toD("ignition-perspective-message:camelComponentBenchmark?sessionId=${header.benchmarkSession}&scope=session")
    builder.from_("direct:perspective-delivered").routeId("bench-perspective-handler-receipt").process(Fn(lambda e: probe.receipt("perspective", e)))
    builder.from_("ignition-gateway-http:/camel-component-benchmark/echo").routeId("bench-http-echo").process(Fn(lambda e: None))
    builder.from_("ignition-gateway-http:/camel-component-benchmark/run").routeId("bench-http-start").setExchangePattern(ExchangePattern.InOnly).to("seda:benchmark-run?size=1&blockWhenFull=false").setBody(builder.constant(obj(accepted=True)))
    builder.from_("seda:benchmark-run?size=1&concurrentConsumers=1").routeId("bench-measurement-worker").process(probe)
    builder.from_("ignition-gateway-http:/camel-component-benchmark/results").routeId("bench-http-results").process(Fn(probe.snapshot))
    builder.from_("ignition-gateway-http:/camel-component-benchmark/samples").routeId("bench-http-samples").process(Fn(probe.samplePage))
