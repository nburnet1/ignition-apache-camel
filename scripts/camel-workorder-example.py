"""Provision the ready-to-use work-order EIP service on the isolated 9288 Gateway.

Existing definitions, scripts, tags and work orders are preserved. An empty demo
is exercised once, leaving active orders and visible completion/audit counters.
"""
import json
import runpy
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]
project = runpy.run_path(str(Path(__file__).with_name("camel-example-project.py")))
PROJECT = project["PROJECT"]
NAME = "camel-example-work-orders"
TOOLS = "camel-example-workorder-tools"
REGISTRY = "com.noahburnette.ignition.apachecamel/camel-registry"
COMPONENT = "com.noahburnette.ignition.apachecamel/camel-component"
TOOL_YAML = """
- route:
    id: workorder-example-setup
    from:
      uri: ignition-gateway-http:/setup?httpMethodRestrict=POST
      steps:
        - to: ignition-script:work_orders.setup?project=CamelExamples
- route:
    id: workorder-example-complete-tag
    from:
      uri: ignition-gateway-http:/complete-tag?httpMethodRestrict=POST
      steps:
        - to: ignition-script:work_orders.completeTag?project=CamelExamples
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
        api("POST", "/data/api/v1/resources/" + kind, [{"name": name, "description": "Work-order EIP example", "config": config}])


def running(name):
    return api("GET", MODULE + "/camel-routes").get(name) == "RUNNING"


def call(operation, payload=None, name=NAME):
    return api("POST", "/system/integrations/" + name + "/" + operation, {} if payload is None else payload)


def snapshot():
    return call("snapshot")


def exercise():
    initial = snapshot()
    if initial["dashboard"]["Submitted"]:
        print("Existing work orders preserved; no sample orders resubmitted.")
        return
    orders = json.loads((ROOT / "examples/camel/work-orders-sample.json").read_text())
    assert call("orders", orders) == "accepted"
    def first_batch():
        report = snapshot()
        dashboard = report["dashboard"]
        return dashboard["Submitted"] == 5 and dashboard["Active"] == 3 and dashboard["Queued"] == 1 and dashboard["Rejected"] == 1
    wait("10-second batch: three assignments, one busy-machine deferral, one rejection", first_batch, seconds=45)
    report = snapshot()
    assert report["machines"]["press-01"]["active"]["id"] == "WO-1001"
    assert report["machines"]["lathe-01"]["active"]["id"] == "WO-1002"
    assert report["machines"]["packer-01"]["active"]["id"] == "WO-1003"
    # The native wildcard subscription observes the signal and calls system.camel.request.
    assert call("complete-tag", {"location": "press-01"}, TOOLS) == "written"
    wait("tag-triggered completion reaches the audit dashboard", lambda: snapshot()["dashboard"]["Completed"] == 1)
    wait("next batch assigns the queued order without overwriting the first", lambda: (snapshot()["machines"]["press-01"].get("active") or {}).get("id") == "WO-1004", seconds=35)
    result = call("complete", {"id": "WO-1002", "location": "lathe-01"})
    assert result["completed"] and result["id"] == "WO-1002", result
    wait("HTTP completion updates Wire Tap counts", lambda: snapshot()["dashboard"]["Completed"] == 2 and snapshot()["dashboard"]["Active"] == 2)
    # Duplicate/stale completions cannot complete a different order or double-count.
    assert not call("complete", {"id": "WO-1001", "location": "press-01"})["completed"]
    assert not call("complete", {"id": "WO-1002", "location": "lathe-01"})["completed"]
    report = snapshot()
    assert report["dashboard"]["Completed"] == 2
    assert report["dashboard"]["Queued"] == 0
    assert report["machines"]["press-01"]["active"]["id"] == "WO-1004"
    assert not report["machines"]["lathe-01"].get("active") and report["machines"]["lathe-01"]["status"] == "IDLE" and not report["machines"]["lathe-01"]["complete"]
    print("Verified 10-second aggregation, bean validation, dynamic machine tags, busy-machine deferral, Wire Tap counts and tag/HTTP completion.")


def verify_model():
    details = api("GET", MODULE + "/camel-route-details?name=" + NAME)
    assert "some-queue" in details["components"], details
    kinds = {step["kind"] for route in details["routes"] for step in route["steps"]}
    assert {"aggregate", "split", "choice", "wireTap", "toD", "process"}.issubset(kinds), kinds
    assert all(route["failed"] == 0 for route in details["routes"]), details


def main():
    wait("isolated Gateway login", m["bootstrap"], seconds=180)
    metadata = api("GET", MODULE + "/camel-component-metadata")["components"]
    assert {"direct", "seda", "timer"}.issubset({item["component"]["scheme"] for item in metadata}), "Activate Direct, SEDA and Timer extensions first"
    project["ensure_project"](m, ("work_orders",))
    existing = find(m["TYPE"], TOOLS)
    own_tools = existing is None or existing.get("config", {}).get("yaml") == TOOL_YAML
    ensure(m["TYPE"], TOOLS, {"yaml": TOOL_YAML})
    try:
        wait("work-order setup route", lambda: running(TOOLS))
        wait("machine folders and dashboard tags", lambda: call("setup", name=TOOLS) == "ready")
        for name, factory in [("wo-service", "serviceFactory"), ("wo-batch", "batchFactory")]:
            ensure(REGISTRY, name, {"type": "BEAN", "bean": {"project": PROJECT, "functionPath": "work_orders." + factory}})
        ensure(COMPONENT, "some-queue", {"type": "GENERIC", "generic": {
            "scheme": "seda", "options": {"queueSize": "1000", "concurrentConsumers": "2"}}})
        component = find(COMPONENT, "some-queue")["config"]
        assert component.get("generic", {}).get("scheme") == "seda", "some-queue must be a SEDA component"
        yaml = (ROOT / "examples/camel/work-orders.yaml").read_text()
        ensure(m["TYPE"], NAME, {"yaml": yaml, "components": ["some-queue"], "registry": ["wo-service", "wo-batch"]})
        wait("work-order EIP service startup", lambda: running(NAME))
        wait("dashboard ready", lambda: snapshot()["dashboard"]["LastEvent"])
        exercise()
        verify_model()
        print(json.dumps(snapshot(), indent=2))
        print("Work-order example left running at http://localhost:9288")
    finally:
        if own_tools:
            m["delete"](TOOLS)


if __name__ == "__main__":
    main()
