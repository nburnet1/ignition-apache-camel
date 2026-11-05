"""Exercise actual extension APIs before removing any existing demo resources."""
from org.apache.activemq.artemis.jms.client import ActiveMQConnectionFactory
from org.apache.camel import Processor
from java.lang import IllegalStateException
import uuid


class Probe(Processor):
    def process(self, exchange):
        name = "eip.preflight." + uuid.uuid4().hex
        factory = ActiveMQConnectionFactory("tcp://artemis:61616", "simulation", "simulation-local-only")
        connection = None
        try:
            connection = factory.createConnection()
            connection.start()
            session = connection.createSession(True, 0)
            try:
                queue = session.createQueue(name)
                producer = session.createProducer(queue)
                producer.send(session.createTextMessage("rolled-back"))
                session.rollback()
                consumer = session.createConsumer(queue)
                assert consumer.receive(200) is None
                producer.send(session.createTextMessage("committed"))
                session.commit()
                received = consumer.receive(2000)
                assert received is not None
                assert received.getText() == "committed"
                session.commit()
                # Real Perspective module invocation, not a stub or skipped test.
                sessions = system.perspective.getSessionInfo()
                exchange.getMessage().setBody({"transaction": "committed", "rollback": True,
                    "perspective": True, "sessions": len(sessions), "queue": name})
            finally:
                session.close()
        finally:
            if connection is not None:
                connection.close()
            factory.close()


def configure(builder):
    builder.from_("ignition-gateway-http:/eip-preflight/probe").routeId("eip-preflight").process(Probe())
    # Resolve the real resilience provider and execute its fallback at startup.
    builder.from_("timer:eip-preflight?repeatCount=1&delay=1000").routeId("eip-preflight-circuit").circuitBreaker().throwException(IllegalStateException("expected preflight failure")).onFallback().setBody(builder.constant("fallback")).end().to("log:eip-preflight?showBody=true")
