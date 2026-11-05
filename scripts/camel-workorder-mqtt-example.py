"""WorkOrders -> named MQTT -> typed mirror tags, isolated Gateway 9288 only."""
import json
import runpy
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(Path(__file__).with_name("camel-workorder-example.py")))
api, wait = m["api"], m["wait"]
NAME = "camel-example-work-orders-mqtt"
TOOLS = "camel-example-work-orders-mqtt-tools"
TOOL_YAML = "\n".join("""
- route:
    from:
      uri: ignition-gateway-http:/%s?httpMethodRestrict=POST
      steps:
        - to: ignition-script:work_order_mqtt.%s?project=CamelExamples
""" % (operation, operation) for operation in ("snapshot", "probe"))


def call(operation, payload=None):
    return api("POST", "/system/integrations/" + TOOLS + "/" + operation, payload or {})


def main():
    wait("isolated Gateway", m["m"]["bootstrap"], seconds=180)
    m["project"]["ensure_project"](m["m"], ("work_order_mqtt",))
    m["ensure"](m["COMPONENT"], "example-mqtt", {"type": "MQTT5", "mqtt": {
        "brokerUrl": "tcp://mqtt:1883", "qos": 1, "cleanStart": True, "sessionExpiryInterval": 0, "connectionTimeout": 5}})
    component = m["find"](m["COMPONENT"], "example-mqtt")
    assert component["config"]["type"] == "MQTT5", "example-mqtt must be an MQTT 5 component"
    existing = m["find"](m["m"]["TYPE"], TOOLS)
    assert existing is None, "Temporary MQTT tools resource already exists; review it first"
    m["ensure"](m["m"]["TYPE"], TOOLS, {"yaml": TOOL_YAML})
    try:
        wait("MQTT helper routes", lambda: m["running"](TOOLS))
        wait("WorkOrders source tags and helper project", lambda: call("snapshot")["sourceCount"] > 0, seconds=40)
        m["ensure"](m["m"]["TYPE"], NAME, {"yaml": (ROOT / "examples/camel/work-orders-mqtt.yaml").read_text(), "components": ["example-mqtt"]})
        wait("WorkOrders MQTT round-trip", lambda: m["running"](NAME), seconds=40)
        wait("initial retained snapshot mirrors every WorkOrders tag", lambda: call("snapshot")["mismatches"] == [], seconds=45)
        value = {"message": "MQTT round-trip " + uuid.uuid4().hex[:8], "flag": True, "count": 12345}
        assert call("probe", value) == "written"
        def delivered():
            report = call("snapshot")
            return not report["mismatches"] and report["samples"]["TransportProbe/Message"]["mirror"] == value["message"]
        wait("new String/Boolean/Int4 tags delivered through the broker", delivered, seconds=35)
        # Reverse Boolean and integer too, proving subsequent changes are not just initial values.
        value.update({"flag": False, "count": -73})
        assert call("probe", value) == "written"
        wait("subsequent typed updates mirrored", lambda: not call("snapshot")["mismatches"], seconds=25)
        details = api("GET", m["MODULE"] + "/camel-route-details?name=" + NAME)
        assert details["metrics"]["failed"] == 0, details
        assert details["diagnostics"]["rejected"] == 0, details
        print(json.dumps(call("snapshot"), indent=2))
        print("Verified WorkOrders -> example-mqtt -> typed mirror, including new tags and live updates. Left running on 9288.")
    finally:
        m["m"]["delete"](TOOLS)


if __name__ == "__main__":
    main()
