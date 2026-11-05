"""Install and verify the introductory YAML examples on the isolated Gateway.

Existing definitions/project scripts are kept, not overwritten. --verify-restart
verifies delivery without reprovisioning. Only example tags/resources are touched.
"""
import json
import runpy
import subprocess
import sys
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]
project = runpy.run_path(str(Path(__file__).with_name("camel-example-project.py")))
PROJECT = project["PROJECT"]
EIP = "camel-example-eip"
UNIFIED = "camel-example-unified-readings"
TOOLS = "camel-example-intro-tools"
COMPONENT = "com.noahburnette.ignition.apachecamel/camel-component"
COMPOSE = ["docker", "compose", "-f", str(ROOT / "docker/camel-acceptance.yml")]
TOOL_YAML = """
- route:
    id: example-setup
    from:
      uri: ignition-gateway-http:/setup?httpMethodRestrict=POST
      steps:
        - to: ignition-script:tags.setup?project=CamelExamples
        - to: ignition-script:unified_readings.setup?project=CamelExamples
        - setBody:
            constant: ready
- route:
    from:
      uri: ignition-gateway-http:/eip-snapshot?httpMethodRestrict=POST
      steps:
        - to: ignition-script:tags.snapshot?project=CamelExamples
- route:
    from:
      uri: ignition-gateway-http:/eip-input?httpMethodRestrict=POST
      steps:
        - to: ignition-script:tags.writeInput?project=CamelExamples
- route:
    from:
      uri: ignition-gateway-http:/unified-snapshot?httpMethodRestrict=POST
      steps:
        - to: ignition-script:unified_readings.snapshot?project=CamelExamples
- route:
    from:
      uri: ignition-gateway-http:/unified-tag?httpMethodRestrict=POST
      steps:
        - to: ignition-script:unified_readings.writeTagInput?project=CamelExamples
"""


def find(kind, name):
    try:
        return api("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise


def ensure(kind, name, config):
    if kind == m["TYPE"]:
        config = {"httpSecurityLevels": ["Authenticated/Roles/Administrator"], **config}
    if find(kind, name) is None:
        api("POST", "/data/api/v1/resources/" + kind, [{"name": name, "description": "Camel YAML example", "config": config}])


def running(name):
    return api("GET", MODULE + "/camel-routes").get(name) == "RUNNING"


def tool(endpoint, body):
    return api("POST", "/system/integrations/" + TOOLS + "/" + endpoint, body)


def install():
    project["ensure_project"](m, ("tags", "unified_readings"))
    ensure(COMPONENT, "example-mqtt", {"type": "MQTT5", "mqtt": {"brokerUrl": "tcp://mqtt:1883", "qos": 1, "cleanStart": True, "sessionExpiryInterval": 0, "connectionTimeout": 5}})
    wait("example project libraries and tags", lambda: tool("setup", {}) == "ready")
    ensure(m["TYPE"], EIP, {"yaml": (ROOT / "examples/camel/eip-to-tags.yaml").read_text()})
    ensure(m["TYPE"], UNIFIED, {"yaml": (ROOT / "examples/camel/multi-source-normalization.yaml").read_text(), "components": ["example-mqtt"]})


def verify():
    for name in (EIP, UNIFIED, TOOLS):
        wait(name + " startup", lambda name=name: running(name))
    readings = [{"sensor": "tank-1", "value": 42, "enabled": True}, {"sensor": "tank-2", "value": 88, "enabled": True}, {"sensor": "disabled", "value": 999, "enabled": False}]
    assert tool("eip-input", readings) == "written"
    assert api("POST", "/system/integrations/" + EIP + "/exercise", readings) == "processed"
    expected = {"High": "tank-2 = 88 (HIGH)", "Normal": "tank-1 = 42 (NORMAL)", "LastRouted": "tank-2 = 88 (HIGH)", "AuditLast": "tank-2 = 88 (HIGH)"}
    def eip_delivered():
        report = tool("eip-snapshot", {})
        return all(report[name]["value"] == value and report[name]["quality"].startswith("Good") for name, value in expected.items())
    wait("split/filter/choice/multicast -> Good output tags", eip_delivered)
    # A disabled-only batch must leave every output unchanged.
    assert api("POST", "/system/integrations/" + EIP + "/exercise", [{"sensor": "ignore", "value": 999, "enabled": False}]) == "processed"
    assert eip_delivered()
    # Verify a change written to the actual input tag, not only HTTP invocation.
    assert tool("eip-input", [{"sensor": "tag-input", "value": 55, "enabled": True}]) == "written"
    wait("real input tag -> EIP graph", lambda: tool("eip-snapshot", {})["Normal"]["value"] == "tag-input = 55 (NORMAL)")
    # Leave the initial demonstration values visible for manual verification.
    assert tool("eip-input", readings) == "written"
    wait("example values restored", eip_delivered)
    details = api("GET", MODULE + "/camel-route-details?name=" + EIP)
    kinds = {step["kind"] for route in details["routes"] for step in route["steps"]}
    assert {"split", "filter", "choice", "multicast"}.issubset(kinds), kinds

    def latest():
        row = tool("unified-snapshot", {})["Latest"]
        assert row["quality"].startswith("Good"), row
        return None if row["value"] == "Waiting for a reading" else json.loads(row["value"])

    http_reading = {"machine": "Press01", "temperatureC": 30}
    api("POST", "/system/integrations/" + UNIFIED + "/readings", http_reading)
    wait("HTTP reading normalized", lambda: latest() == {
        "machine": "Press01", "temperatureC": 30.0, "status": "NORMAL", "source": "http"})

    mqtt_reading = {"device": "Press02", "temperatureF": 194}
    subprocess.run(COMPOSE + ["exec", "-T", "mqtt", "mosquitto_pub", "-V", "mqttv5",
        "-h", "localhost", "-t", "camel-demo/readings", "-q", "1",
        "-m", json.dumps(mqtt_reading)], check=True, timeout=15)
    wait("MQTT reading normalized", lambda: latest() == {
        "machine": "Press02", "temperatureC": 90.0, "status": "HIGH", "source": "mqtt"})

    current = float(tool("unified-snapshot", {})["RawTemperature"]["value"])
    tag_value = 46 if current == 45 else 45
    assert tool("unified-tag", {"value": tag_value}) == "written"
    wait("tag reading normalized", lambda: latest() == {
        "machine": "Press03", "temperatureC": float(tag_value), "status": "NORMAL",
        "source": "ignition-tag"})
    unified = api("GET", MODULE + "/camel-route-details?name=" + UNIFIED)
    assert {route["id"] for route in unified["routes"]} == {
        "unified-http-input", "unified-mqtt-input", "unified-tag-input",
        "unified-reading-service"}
    print("YAML examples verified: route a batch with EIPs; normalize HTTP, MQTT and tag inputs into one model.")
    print(json.dumps({"eip": tool("eip-snapshot", {}), "unified": latest()}, indent=2))


def main():
    wait("isolated Gateway login", m["bootstrap"], seconds=180)
    existing = find(m["TYPE"], TOOLS)
    stored_tools_yaml = m["fixture_http_yaml"](TOOLS, TOOL_YAML)
    if existing is not None and existing.get("config", {}).get("yaml") != stored_tools_yaml:
        raise ValueError("Temporary intro tools resource already exists with different YAML: " + TOOLS)
    own_tools = existing is None
    ensure(m["TYPE"], TOOLS, {"yaml": TOOL_YAML})
    try:
        wait("example setup routes", lambda: running(TOOLS))
        if "--verify-restart" not in sys.argv:
            install()
        verify()
    finally:
        if own_tools:
            m["delete"](TOOLS)


if __name__ == "__main__":
    main()
