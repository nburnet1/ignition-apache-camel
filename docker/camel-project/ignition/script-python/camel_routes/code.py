from org.apache.camel import Processor

delta = 1


class Increment(Processor):
    def process(self, exchange):
        exchange.getMessage().setBody(int(exchange.getMessage().getBody()) + delta)


def orders(builder):
    (builder.from_("timer:jython?repeatCount=1&delay=1000")
        .routeId("jython-proof")
        .setBody().constant(41)
        .process(Increment())
        .to("ignition-script:camel_fixture.jythonProof?project=camel_acceptance"))
