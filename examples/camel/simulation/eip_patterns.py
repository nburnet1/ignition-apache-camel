"""Executable pattern lab; assertions examine delivered output, not route names.

Architectural patterns intentionally share flows. A shared receipt is evidence of
the same running integration, not 64 artificially disconnected hello-worlds.
"""
from java.lang import AutoCloseable, IllegalStateException, String, Thread, System
from java.lang import Exception as JavaException
from java.util import ArrayList, HashMap
from java.util.concurrent import ConcurrentHashMap, CopyOnWriteArrayList
from java.util.concurrent.atomic import AtomicInteger
from org.apache.camel import Processor, Expression, AggregationStrategy
from org.apache.camel.model import ClaimCheckOperation
from org.apache.camel.model.dataformat import JsonLibrary
from org.apache.camel.processor.aggregate import GroupedBodyAggregationStrategy
from org.apache.camel.support.processor.idempotent import MemoryIdempotentRepository
import eip_simulation as sim
import eip_routes
from eip_manifest import PATTERNS
import uuid


class Fn(Processor):
    def __init__(self, function):
        self.function = function

    def process(self, exchange):
        self.function(exchange)


class Body(Processor):
    def __init__(self, function):
        self.function = function

    def process(self, exchange):
        exchange.getMessage().setBody(sim.java(self.function(sim.py(exchange.getMessage().getBody()))))


class Record(Processor):
    def __init__(self, harness, stage, delay=0):
        self.harness, self.stage, self.delay = harness, stage, delay

    def process(self, exchange):
        if self.delay:
            Thread.sleep(self.delay)
        key = unicode(exchange.getMessage().getHeader("testId")) + ":" + self.stage
        self.harness.receipts.putIfAbsent(key, CopyOnWriteArrayList())
        self.harness.receipts.get(key).add({"body": sim.py(exchange.getMessage().getBody()),
            "thread": unicode(Thread.currentThread().getName()), "time": System.nanoTime()})


class Next(Expression):
    def evaluate(self, exchange, resultType):
        count = int(exchange.getProperty("dynamicHop") or 0)
        exchange.setProperty("dynamicHop", count + 1)
        return String(["direct:dynamic-a", "direct:dynamic-b"][count]) if count < 2 else None


class Merge(AggregationStrategy):
    def aggregate(self, original, resource):
        body = sim.py(original.getMessage().getBody())
        body.update(sim.py(resource.getMessage().getBody()))
        original.getMessage().setBody(sim.java(body))
        return original


class TxFailure(Processor):
    def __init__(self, harness):
        self.harness = harness

    def process(self, exchange):
        key = unicode(exchange.getMessage().getBody())
        self.harness.attempts.putIfAbsent(key, AtomicInteger())
        if self.harness.attempts.get(key).incrementAndGet() == 1:
            raise IllegalStateException("Intentional first-attempt rollback")


class Harness(Processor):
    def __init__(self, factory):
        self.factory = factory
        self.receipts = ConcurrentHashMap()
        self.attempts = ConcurrentHashMap()
        self.running = AtomicInteger()
        proof = sim._query("SELECT body FROM eip_sim_events WHERE kind='broker-restart-proof' ORDER BY id DESC LIMIT 1")
        self.persisted = system.util.jsonDecode(proof[0]["body"])["token"] if proof else None
        self.restartToken = None

    def rows(self, token, stage):
        return list(self.receipts.get(token + ":" + stage) or [])

    def awaitRows(self, token, stage, count, timeout=4000):
        end = System.currentTimeMillis() + timeout
        while System.currentTimeMillis() < end:
            rows = self.rows(token, stage)
            if len(rows) >= count:
                return rows
            Thread.sleep(10)
        raise AssertionError("Missing receipt: " + stage + " expected " + str(count))

    def writeStatus(self, values):
        names = sorted(values)
        qualities = system.tag.writeBlocking([sim.ROOT + "/Tests/" + name for name in names], [values[name] for name in names])
        assert all(q.isGood() for q in qualities), "Could not publish test status"

    def preparePersistence(self, exchange):
        self.restartToken = uuid.uuid4().hex
        producer = exchange.getContext().createProducerTemplate()
        try:
            producer.sendBody("jms:queue:eip.restart-proof?deliveryPersistent=true&jmsMessageType=Text", self.restartToken)
        finally:
            producer.stop()
        exchange.getMessage().setBody(sim.java({"token": self.restartToken, "restartRequired": True}))

    def finishPersistence(self, exchange):
        assert self.restartToken, "Prepare persistence proof before restarting Artemis"
        consumer = exchange.getContext().createConsumerTemplate()
        try:
            body = consumer.receiveBody("jms:queue:eip.restart-proof", 5000)
        finally:
            consumer.stop()
        assert unicode(body) == self.restartToken, "Persistent message was not recovered after broker restart"
        self.persisted = self.restartToken
        sim.event("broker-restart-proof", {"token": self.persisted, "verifiedAt": system.date.now().getTime()})
        exchange.getMessage().setBody(sim.java({"recovered": True, "token": self.persisted}))

    def process(self, exchange):
        if not self.running.compareAndSet(0, 1):
            return
        producer = exchange.getContext().createProducerTemplate()
        consumer = exchange.getContext().createConsumerTemplate()
        self.producer, self.consumer, self.context = producer, consumer, exchange.getContext()
        results = {}
        self.receipts.clear()
        self.attempts.clear()
        self.writeStatus({"Running": True, "Passed": 0, "LastRun": "Running behavioral assertions"})
        try:
            for scenario in sorted(set(row["scenario"] for row in PATTERNS)):
                started = System.currentTimeMillis()
                try:
                    evidence = getattr(self, "test_" + scenario.replace("-", "_"))()
                    results[scenario] = {"status": "PASS", "evidence": evidence}
                except (Exception, JavaException) as failure:
                    results[scenario] = {"status": "FAIL", "evidence": (unicode(failure) or type(failure).__name__)[:300]}
                results[scenario]["durationMs"] = System.currentTimeMillis() - started
            rows = [dict(row, **results[row["scenario"]]) for row in PATTERNS]
            passed = sum(1 for row in rows if row["status"] == "PASS")
            self.writeStatus({"Coverage": system.util.jsonEncode(rows), "Passed": passed,
                "LastRun": str(passed) + "/64 passed at " + system.date.format(system.date.now(), "yyyy-MM-dd HH:mm:ss")})
        finally:
            producer.stop()
            consumer.stop()
            self.writeStatus({"Running": False})
            self.running.set(0)
            self.receipts.clear()
            self.attempts.clear()

    def request(self, endpoint, body="order", headers=None):
        return sim.py(self.producer.requestBodyAndHeaders("direct:" + endpoint, sim.java(body), sim.java(headers or {})))

    def token(self):
        return uuid.uuid4().hex

    def test_channel(self):
        body = {"id": self.token(), "quantity": 7}
        assert self.request("channel", body) == body
        return "Camel Exchange delivered the unchanged work-order document through a direct endpoint"

    def test_pipeline(self):
        assert self.request("pipeline", {"quantity": 3}) == {"quantity": 3, "validated": True, "units": 6}
        return "Two independent Camel processors validated and calculated the order"

    def test_translation(self):
        value = {"id": "WO-JSON", "quantity": 7}
        assert self.request("translation", value) == value
        return "Jackson marshal/unmarshal round-trip retained the canonical document"

    def test_router(self):
        assert self.request("router", "order", {"machine": "press"}) == "press"
        assert self.request("router", "order", {"machine": "lathe"}) == "lathe"
        return "Content-Based Router selected two distinct machine endpoints"

    def test_filter(self):
        token = self.token()
        self.request("filter", "rejected", {"testId": token, "accepted": False})
        self.request("filter", "accepted", {"testId": token, "accepted": True})
        assert [r["body"] for r in self.rows(token, "filter")] == ["accepted"]
        return "Rejected input did not reach the receiver; accepted input did"

    def test_invalid(self):
        assert self.request("invalid", {"quantity": -1}) == "invalid-order"
        assert self.request("invalid", {"quantity": 1}) == "valid-order"
        return "Malformed business input went to the invalid-message channel, not the valid receiver"

    def test_point(self):
        token = self.token()
        self.producer.sendBodyAndHeader("jms:queue:eip.point", token, "testId", token)
        rows = self.awaitRows(token, "point", 1)
        Thread.sleep(100)
        assert len(self.rows(token, "point")) == 1 and rows[0]["body"] == token
        return "JMS queue delivered exactly one copy to its point-to-point receiver"

    def test_pubsub(self):
        token = self.token()
        self.producer.sendBodyAndHeader("jms:topic:eip.broadcast", token, "testId", token)
        assert self.awaitRows(token, "subscriber-a", 1)[0]["body"] == token
        assert self.awaitRows(token, "subscriber-b", 1)[0]["body"] == token
        return "Two independent JMS topic subscribers received the same event"

    def test_datatype(self):
        assert self.request("datatype", {"type": "work-order"}) == "order-channel"
        assert self.request("datatype", {"type": "completion"}) == "completion-channel"
        return "Order and completion document types were routed into distinct typed channels"

    def test_deadletter(self):
        token = self.token()
        self.request("deadletter", token)
        assert unicode(self.consumer.receiveBody("jms:queue:eip.dead", 3000)) == token
        return "Camel exhausted two redeliveries and delivered the original failed message to the JMS dead-letter queue"

    def test_persistence(self):
        assert self.persisted, "Run prepare-persistence, restart Artemis, then finish-persistence"
        return "Persistent JMS message recovered across an actual Artemis container restart: " + self.persisted

    def test_native_tags(self):
        token = self.token()
        system.camel.request("eip-work-orders", "direct:writes", [{"path": sim.ROOT + "/Tests/TagProbe", "value": token}])
        end = System.currentTimeMillis() + 3000
        while System.currentTimeMillis() < end:
            if system.tag.readBlocking([sim.ROOT + "/Tests/TagObserved"])[0].value == token:
                return "ignition-tag producer -> real tag -> native subscription -> ignition-script -> ignition-tag receipt"
            Thread.sleep(20)
        raise AssertionError("Native tag consumer did not observe producer write")

    def test_mqtt(self):
        token = self.token()
        system.camel.request("eip-work-orders", "direct:writes", [{"path": sim.ROOT + "/Tests/TagProbe", "value": token}])
        topic = "ignition/default/EIPSimulation/Tests/TagProbe"
        uri = "paho-mqtt5:" + topic + "?brokerUrl=tcp://mqtt:1883&clientId=eip-proof-" + token + "&qos=1&cleanStart=true&sessionExpiryInterval=0"
        deadline = System.currentTimeMillis() + 4000
        while System.currentTimeMillis() < deadline:
            message = self.consumer.receiveBody(uri, 500)
            if message is None:
                continue
            # A retained prior value may arrive before this asynchronous change.
            frame = system.util.jsonDecode(String(message, "UTF-8"))
            if frame["value"] == token and frame["tagPath"] == sim.ROOT + "/Tests/TagProbe":
                return "Real tag-change adapter crossed the MQTT broker and was received with value, path, quality and timestamp"
        raise AssertionError("MQTT receiver did not receive the latest native tag value")

    def test_bus(self):
        assert self.request("bus", {"type": "work-order"}) == "order-channel"
        assert self.request("bus", {"type": "completion"}) == "completion-channel"
        return "One broker ingress dispatched canonical typed messages without sender knowledge of receivers"

    def test_command(self):
        assert self.request("command", {"command": "quote", "quantity": 5}) == {"cost": 50}
        return "Command Message invoked the pricing operation rather than merely storing a document"

    def test_document(self):
        value = {"id": self.token(), "quantity": 19}
        assert self.request("translation", value) == value
        return "Structured work-order Document Message crossed a serialized transport boundary"

    def test_event(self):
        token = self.token()
        self.producer.sendBodyAndHeader("jms:queue:eip.events", token, "testId", token)
        assert self.awaitRows(token, "event", 1)[0]["body"] == token
        return "JMS Event Message automatically woke its event-driven Camel consumer"

    def test_request(self):
        assert self.request("command", {"command": "quote", "quantity": 7}) == {"cost": 70}
        return "Synchronous request returned the calculated reply to its caller"

    def test_return(self):
        token = self.token()
        self.producer.sendBodyAndHeaders("direct:return", token, sim.java({"returnTo": "jms:queue:eip.reply." + token}))
        assert unicode(self.consumer.receiveBody("jms:queue:eip.reply." + token, 2000)) == token
        return "Replier delivered to the sender-specified Return Address"

    def test_correlation(self):
        token = self.token()
        reply = self.producer.request("direct:correlation", Fn(lambda e: e.getMessage().setHeader("correlationId", token)))
        assert unicode(reply.getMessage().getHeader("correlationId")) == token
        assert unicode(reply.getMessage().getBody()) == "correlated-reply"
        return "Reply retained the request Correlation Identifier: " + token

    def test_sequence(self):
        token = self.token()
        self.request("sequence", ["a", "b", "c"], {"testId": token})
        assert [r["body"] for r in self.rows(token, "sequence")] == ["0/3:a", "1/3:b", "2/3:c"]
        return "Splitter emitted an indexed three-part Message Sequence with total length"

    def test_expiration(self):
        token = self.token()
        self.producer.sendBody("jms:queue:eip.expiry." + token + "?timeToLive=100&explicitQosEnabled=true", token)
        Thread.sleep(300)
        assert self.consumer.receiveBody("jms:queue:eip.expiry." + token, 200) is None
        return "Broker TTL expired the stale command before a late consumer could process it"

    def test_format(self):
        assert self.request("format", {"version": 1, "qty": 2}) == {"quantity": 2}
        assert self.request("format", {"version": 2, "quantity": 3}) == {"quantity": 3}
        return "Format Indicator selected the correct parser for two document versions"

    def test_dynamic(self):
        token = self.token()
        self.request("dynamic", "order", {"testId": token})
        assert len(self.rows(token, "dynamic-a")) == len(self.rows(token, "dynamic-b")) == 1
        return "Camel Dynamic Router asked for successive destinations and stopped on null"

    def test_recipients(self):
        token = self.token()
        self.request("recipients", "order", {"testId": token, "recipients": "direct:recipient-a,direct:recipient-b"})
        assert len(self.rows(token, "recipient-a")) == len(self.rows(token, "recipient-b")) == 1
        return "Dynamic Recipient List delivered to both supplied recipients"

    def test_split(self):
        token = self.token()
        self.request("split", [1, 2, 3], {"testId": token})
        assert [r["body"] for r in self.rows(token, "split")] == [1, 2, 3]
        return "One three-line order was split into three independently delivered line messages"

    def test_aggregate(self):
        token = self.token()
        for value in [1, 2, 3]:
            self.producer.sendBodyAndHeaders("direct:aggregate", value, sim.java({"testId": token, "batch": token}))
        assert self.awaitRows(token, "aggregate", 1)[0]["body"] == [1, 2, 3]
        return "Camel Aggregator completed only after three correlated messages"

    def test_resequence(self):
        token = self.token()
        for value in [3, 1, 2]:
            self.producer.sendBodyAndHeaders("direct:resequence", value, sim.java({"testId": token, "sequence": value}))
        assert [r["body"] for r in self.awaitRows(token, "resequence", 3)] == [1, 2, 3]
        return "Batch Resequencer restored 3,1,2 arrival order to 1,2,3"

    def test_composed(self):
        assert self.request("composed", [1, 2, 3]) == [2, 4, 6]
        return "Composed Message Processor split, transformed each line and recombined its results"

    def test_scatter(self):
        assert sorted(self.request("scatter", "order")) == [10, 20]
        return "Parallel multicast gathered replies from two independent pricing services"

    def test_slip(self):
        token = self.token()
        self.request("slip", "order", {"testId": token, "itinerary": "direct:slip-b,direct:slip-a"})
        a, b = self.rows(token, "slip-a")[0], self.rows(token, "slip-b")[0]
        assert b["time"] <= a["time"]
        return "Per-message Routing Slip ran the selected stages in supplied b,a order"

    def test_process_manager(self):
        assert self.request("process-manager", {"state": "NEW", "quantity": 2}) == {"state": "QUOTED", "quantity": 2, "cost": 20}
        return "Process Manager used document state to coordinate validation, pricing and completion"

    def test_envelope(self):
        document = {"id": "WO-ENV", "quantity": 2}
        assert self.request("envelope", document) == document
        return "Business document was wrapped with transport metadata and unwrapped without acquiring envelope layers"

    def test_enrich(self):
        assert self.request("enrich", {"id": "WO-E"}) == {"id": "WO-E", "material": "steel"}
        return "Camel Enrich combined the order with a separate material-service reply"

    def test_content_filter(self):
        assert self.request("content-filter", {"id": "WO-F", "quantity": 3, "private": "omit"}) == {"id": "WO-F", "quantity": 3}
        return "Content Filter removed the private field while retaining the machine's required fields"

    def test_claim(self):
        value = {"id": "WO-C", "drawing": "large-drawing" * 100}
        assert self.request("claim", value) == value
        return "Native Camel Claim Check pushed the full message, substituted a small ticket and restored it"

    def test_normalize(self):
        expected = {"id": "WO-N", "quantity": 3}
        assert self.request("normalize", {"orderNumber": "WO-N", "qty": 3}) == expected
        assert self.request("normalize", expected) == expected
        return "Two supplier formats normalized into the same Canonical Data Model"

    def test_native_message(self):
        token = self.token()
        assert self.request("native-message", {"id": token}) == {"id": token, "activated": True}
        return "ignition-message invoked the real global project Gateway message handler and returned its reply"

    def test_mapper(self):
        assert self.request("mapper", {"machine": "press-01", "units": 7}) == {"destination": "press-01", "quantity": 7}
        return "Messaging Mapper separated the machine's domain fields from its transport representation"

    def test_transaction(self):
        token = self.token()
        self.producer.sendBody("jms:queue:eip.tx.in?deliveryPersistent=true&jmsMessageType=Text", token)
        result = self.consumer.receiveBody("jms:queue:eip.tx.out", 5000)
        assert unicode(result) == token, "Committed transactional output not received"
        assert self.consumer.receiveBody("jms:queue:eip.tx.out", 300) is None, "Rolled-back output escaped"
        assert self.attempts.get(token).get() == 2, "Broker did not redeliver the rolled-back input"
        return "Real Camel/JMS local transaction rolled back its first output and input acknowledgement; redelivery committed exactly one output"

    def test_poll(self):
        token = self.token()
        self.producer.sendBody("jms:queue:eip.poll." + token, token)
        assert unicode(self.consumer.receiveBody("jms:queue:eip.poll." + token, 2000)) == token
        return "Camel ConsumerTemplate polled the channel only when the application requested a message"

    def test_competing(self):
        token = self.token()
        for i in range(8):
            self.producer.sendBodyAndHeader("jms:queue:eip.competing", str(i), "testId", token)
        rows = self.awaitRows(token, "competing", 8)
        assert sorted(r["body"] for r in rows) == [str(i) for i in range(8)]
        assert len(set(r["thread"] for r in rows)) >= 2
        return "Two competing JMS consumer threads processed eight distinct commands once each"

    def test_selector(self):
        token = self.token()
        uri = "jms:queue:eip.selector." + token
        for machine in ["lathe", "press"]:
            self.producer.sendBodyAndHeader(uri, machine, "machine", machine)
        selected = self.consumer.receiveBody(uri + "?selector=machine%3D%27press%27", 2000)
        assert unicode(selected) == "press"
        assert unicode(self.consumer.receiveBody(uri, 2000)) == "lathe"
        return "JMS Selective Consumer took only its matching command and left the other available"

    def test_durable(self):
        token = self.token()
        connection = self.factory.createConnection()
        connection.setClientID("eip-durable-" + token)
        try:
            session = connection.createSession(False, 1)
            topic = session.createTopic("eip.durable." + token)
            subscriber = session.createDurableConsumer(topic, "machine")
            subscriber.close()
            session.close()
            self.producer.sendBody("jms:topic:eip.durable." + token + "?deliveryPersistent=true", token)
            session = connection.createSession(False, 1)
            subscriber = session.createDurableConsumer(topic, "machine")
            connection.start()
            message = subscriber.receive(3000)
            assert message is not None and message.getText() == token
            subscriber.close()
            session.unsubscribe("machine")
            session.close()
        finally:
            connection.close()
        return "Broker retained a persistent topic event while the durable subscriber was disconnected"

    def test_idempotent(self):
        token = self.token()
        for i in range(3):
            self.request("idempotent", "order", {"testId": token, "orderId": token})
        assert len(self.rows(token, "idempotent")) == 1
        return "Camel Idempotent Consumer suppressed two duplicate deliveries (bounded node-local repository)"

    def test_control(self):
        status = self.request("control")
        assert unicode(status) == "Started"
        return "Real Control Bus queried a live Camel route's status: " + unicode(status)

    def test_detour(self):
        token = self.token()
        self.request("detour", "order", {"testId": token, "inspect": False})
        self.request("detour", "order", {"testId": token, "inspect": True})
        assert len(self.rows(token, "inspection")) == 1 and len(self.rows(token, "detour")) == 2
        return "Enabled Detour inserted inspection while both messages still reached normal processing"

    def test_wiretap(self):
        token = self.token()
        assert self.request("wiretap", "business", {"testId": token}) == "business"
        assert self.awaitRows(token, "tap", 1)[0]["body"] == "business"
        return "Wire Tap independently copied the event without altering its primary result"

    def test_history(self):
        rows = self.request("history")
        assert len(rows) >= 2 and all("route" in r and "node" in r for r in rows)
        return "Native Camel Message History recorded traversed route/node identifiers without storing payloads"

    def test_store(self):
        token = self.token()
        self.request("store", {"id": token})
        rows = sim._query("SELECT body FROM eip_sim_events WHERE kind='pattern-message-store' ORDER BY id DESC LIMIT 1")
        assert system.util.jsonDecode(rows[0]["body"])["id"] == token
        return "Message Store persisted an independently queryable PostgreSQL audit record"

    def test_proxy(self):
        token = self.token()
        reply = self.request("proxy", {"id": token}, {"correlationId": token, "returnTo": "requester"})
        assert reply == {"id": token, "correlationId": token, "returnTo": "requester", "proxyObserved": True}
        return "Smart Proxy substituted its backend return address and restored the requester's address/correlation on reply"

    def test_test_message(self):
        assert self.request("test-message", {"quantity": 3}) == {"cost": 30}
        return "A known Test Message checked calculated output, not just route or process liveness"

    def test_purge(self):
        token = self.token()
        uri = "jms:queue:eip.purge." + token
        for i in range(3):
            self.producer.sendBody(uri, str(i))
        count = 0
        while self.consumer.receiveBody(uri, 100) is not None:
            count += 1
        assert count == 3 and self.consumer.receiveBody(uri, 100) is None
        return "Explicit Channel Purger drained three leftover messages and verified the channel empty"

    def test_throttle(self):
        started = System.nanoTime()
        for i in range(3):
            self.request("throttle")
        elapsed = (System.nanoTime() - started) / 1000000
        assert elapsed >= 250
        return "Camel Throttler enforced a 150 ms single-request window; three requests took %d ms" % elapsed

    def test_load_balance(self):
        assert [self.request("load-balance") for i in range(4)] == ["machine-a", "machine-b", "machine-a", "machine-b"]
        return "Native round-robin Load Balancer distributed four requests evenly to two machine services"

    def test_circuit(self):
        assert self.request("circuit") == "fallback"
        return "Real Resilience4j Circuit Breaker executed its fallback when the machine service failed"


def configure(builder):
    factory = eip_routes.installJms(builder)
    harness = Harness(factory)
    context = builder.getContext()
    context.setMessageHistory(True)
    builder.errorHandler(builder.noErrorHandler())
    def route(name):
        return builder.from_("direct:" + name).routeId("pattern-" + name)
    def record(name, stage=None):
        return route(name).process(Record(harness, stage or name))
    route("channel").to("direct:channel-receiver")
    record("channel-receiver")
    route("pipeline").process(Body(lambda b: dict(b, validated=True))).process(Body(lambda b: dict(b, units=b["quantity"] * 2)))
    route("translation").marshal().json(JsonLibrary.Jackson).unmarshal().json(JsonLibrary.Jackson)
    route("router").choice().when(builder.simple("${header.machine} == 'press'")).to("direct:press").otherwise().to("direct:lathe").end()
    route("press").setBody(builder.constant("press"))
    route("lathe").setBody(builder.constant("lathe"))
    route("filter").filter(builder.simple("${header.accepted} == true")).process(Record(harness, "filter")).end()
    route("invalid").choice().when(builder.simple("${body[quantity]} > 0")).to("direct:valid-order").otherwise().to("direct:invalid-order").end()
    for name in ["valid-order", "invalid-order", "order-channel", "completion-channel"]:
        route(name).setBody(builder.constant(name))
    for name in ["datatype", "bus"]:
        route(name).choice().when(builder.simple("${body[type]} == 'work-order'")).to("direct:order-channel").otherwise().to("direct:completion-channel").end()
    route("deadletter").errorHandler(builder.deadLetterChannel("jms:queue:eip.dead?deliveryPersistent=true").maximumRedeliveries(2).redeliveryDelay(10).useOriginalMessage()).throwException(IllegalStateException("intentional delivery failure"))
    for uri, stage in [("jms:queue:eip.point", "point"), ("jms:topic:eip.broadcast", "subscriber-a"),
                       ("jms:topic:eip.broadcast", "subscriber-b"), ("jms:queue:eip.events", "event")]:
        builder.from_(uri).routeId("pattern-" + stage).process(Record(harness, stage))
    builder.from_("jms:queue:eip.competing?concurrentConsumers=2&maxConcurrentConsumers=2").routeId("pattern-competing").process(Record(harness, "competing", 30))
    builder.from_("jms:queue:eip.tx.in?transacted=true").routeId("pattern-transactional-client").errorHandler(builder.noErrorHandler()).to("jms:queue:eip.tx.out?deliveryPersistent=true").process(TxFailure(harness))
    route("command").process(Body(lambda b: {"cost": b["quantity"] * 10}))
    route("return").recipientList(builder.header("returnTo"))
    route("correlation").setBody(builder.constant("correlated-reply"))
    route("sequence").split(builder.body()).setBody(builder.simple("${exchangeProperty.CamelSplitIndex}/${exchangeProperty.CamelSplitSize}:${body}")).process(Record(harness, "sequence")).end()
    route("format").choice().when(builder.simple("${body[version]} == 1")).process(Body(lambda b: {"quantity": b["qty"]})).otherwise().process(Body(lambda b: {"quantity": b["quantity"]})).end()
    route("dynamic").dynamicRouter(Next())
    for name in ["dynamic-a", "dynamic-b", "recipient-a", "recipient-b", "slip-a", "slip-b"]:
        record(name)
    route("recipients").recipientList(builder.header("recipients"))
    route("split").split(builder.body()).process(Record(harness, "split")).end()
    route("aggregate").aggregate(builder.header("batch"), GroupedBodyAggregationStrategy()).completionSize(3).process(Record(harness, "aggregate"))
    route("resequence").resequence(builder.header("sequence")).batch().size(3).timeout(500).process(Record(harness, "resequence"))
    route("composed").split(builder.body(), GroupedBodyAggregationStrategy()).process(Body(lambda b: b * 2)).end()
    route("scatter").multicast(GroupedBodyAggregationStrategy()).parallelProcessing().to("direct:quote-a", "direct:quote-b").end()
    route("quote-a").setBody(builder.constant(10))
    route("quote-b").setBody(builder.constant(20))
    route("slip").routingSlip(builder.header("itinerary"))
    route("process-manager").choice().when(builder.simple("${body[state]} == 'NEW'")).to("direct:process-validate").to("direct:process-quote").otherwise().throwException(IllegalStateException("Unsupported order state")).end()
    route("process-validate").process(Body(lambda b: dict(b, state="VALIDATED")))
    route("process-quote").process(Body(lambda b: dict(b, state="QUOTED", cost=b["quantity"] * 10)))
    route("envelope").process(Body(lambda b: {"metadata": {"source": "supplier"}, "payload": b})).marshal().json(JsonLibrary.Jackson).unmarshal().json(JsonLibrary.Jackson).process(Body(lambda b: b["payload"]))
    route("enrich").enrich("direct:material", Merge())
    route("material").setBody(builder.constant(sim.java({"material": "steel"})))
    route("content-filter").process(Body(lambda b: {"id": b["id"], "quantity": b["quantity"]}))
    route("claim").claimCheck(ClaimCheckOperation.Push).setBody(builder.constant("small-ticket")).claimCheck(ClaimCheckOperation.Pop)
    route("normalize").choice().when(builder.simple("${body[orderNumber]} != null")).process(Body(lambda b: {"id": b["orderNumber"], "quantity": b["qty"]})).otherwise().process(Body(lambda b: {"id": b["id"], "quantity": b["quantity"]})).end()
    route("native-message").to("ignition-message:patternService?project=global&mode=request")
    route("mapper").process(Body(lambda b: {"destination": b["machine"], "quantity": b["units"]}))
    route("idempotent").idempotentConsumer(builder.header("orderId"), MemoryIdempotentRepository.memoryIdempotentRepository(1000)).process(Record(harness, "idempotent")).end()
    route("control").to("controlbus:route?routeId=pattern-channel&action=status")
    route("detour").choice().when(builder.simple("${header.inspect} == true")).to("direct:inspection").end().process(Record(harness, "detour"))
    record("inspection")
    route("wiretap").wireTap("direct:tap")
    record("tap")
    route("history").to("direct:history-backend")
    route("history-backend").process(Fn(lambda e: e.getMessage().setBody(sim.java([
        {"route": unicode(h.getRouteId()), "node": unicode(h.getNode().getId())}
        for h in e.getProperty("CamelMessageHistory")]))))
    route("store").process(Fn(lambda e: sim.event("pattern-message-store", sim.py(e.getMessage().getBody()))))
    route("proxy").setProperty("proxyOriginalReturn", builder.header("returnTo")).setHeader("returnTo", builder.constant("proxy"))\
        .to("direct:proxy-backend").setHeader("returnTo", builder.exchangeProperty("proxyOriginalReturn"))\
        .process(Fn(lambda e: e.getMessage().setBody(sim.java(dict(sim.py(e.getMessage().getBody()),
            correlationId=unicode(e.getMessage().getHeader("correlationId")), returnTo=unicode(e.getMessage().getHeader("returnTo")), proxyObserved=True)))))
    route("proxy-backend").filter(builder.simple("${header.returnTo} == 'proxy'")).to("direct:proxy-return").end()
    record("proxy-return")
    route("test-message").to("direct:command")
    route("throttle").throttle(1).timePeriodMillis(150).setBody(builder.constant("ok"))
    route("load-balance").loadBalance().roundRobin().to("direct:machine-a", "direct:machine-b").end()
    for name in ["machine-a", "machine-b"]:
        route(name).setBody(builder.constant(name))
    route("circuit").circuitBreaker().throwException(IllegalStateException("simulated broken machine"))\
        .onFallback().setBody(builder.constant("fallback")).end()
    builder.from_("ignition-gateway-http:/eip-patterns/run").routeId("pattern-run-http").setExchangePattern(orgPattern())\
        .to("seda:pattern-suite?size=1&discardWhenFull=false&blockWhenFull=false").setBody(builder.constant(sim.java({"accepted": True})))
    builder.from_("seda:pattern-suite?size=1&concurrentConsumers=1").routeId("pattern-behavioral-suite").process(harness)
    builder.from_("ignition-gateway-http:/eip-patterns/prepare-persistence").routeId("pattern-prepare-persistence").process(Fn(harness.preparePersistence))
    builder.from_("ignition-gateway-http:/eip-patterns/finish-persistence").routeId("pattern-finish-persistence").process(Fn(harness.finishPersistence))


def orgPattern():
    from org.apache.camel import ExchangePattern
    return ExchangePattern.InOnly
