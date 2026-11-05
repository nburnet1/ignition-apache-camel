"""Install a running two-second heartbeat and verify real Control Bus suspend/resume.

Uses only the isolated Gateway APIs on 9288. Existing example definitions and
scripts are preserved. Temporary setup/inspection routes are removed afterward.
"""
import json
import runpy
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]
NAME = "camel-example-controlbus"
project = runpy.run_path(str(Path(__file__).with_name("camel-example-project.py")))
PROJECT = project["PROJECT"]
TOOLS = "camel-example-controlbus-tools"
TOOL_YAML = "\n".join("""
- route:
    id: controlbus-example-%s
    from:
      uri: ignition-gateway-http:/%s?httpMethodRestrict=POST
      steps:
        - to: ignition-script:control_bus.%s?project=CamelExamples
""" % (operation, operation, operation) for operation in ("setup", "snapshot", "command"))


def find(name):
    return m["find"](name)


def ensure(name, yaml):
    if find(name) is None:
        api("POST", "/data/api/v1/resources/" + m["TYPE"], [{"name": name, "description": "Control Bus heartbeat example", "config": {"yaml": yaml, "httpSecurityLevels": ["Authenticated/Roles/Administrator"]}}])


def running(name):
    return api("GET", MODULE + "/camel-routes").get(name) == "RUNNING"


def tool(operation, payload=None):
    return api("POST", "/system/integrations/" + TOOLS + "/" + operation, {} if payload is None else payload)


def snapshot():
    report = tool("snapshot")
    assert all(value["quality"].startswith("Good") for value in report.values()), report
    return report


def install_project():
    project["ensure_project"](m, ("control_bus",))


def main():
    wait("isolated Gateway login", m["bootstrap"], seconds=180)
    metadata = api("GET", MODULE + "/camel-component-metadata")["components"]
    installed = {item["component"]["scheme"] for item in metadata}
    assert {"timer", "controlbus"}.issubset(installed), "Install/activate the Timer and Control Bus extensions first"
    if "--verify-restart" not in sys.argv:
        install_project()
    existing = find(TOOLS)
    own_tools = existing is None or existing.get("config", {}).get("yaml") == TOOL_YAML
    ensure(TOOLS, TOOL_YAML)
    paused_by_test = False
    try:
        wait("Control Bus inspection routes", lambda: running(TOOLS))
        if "--verify-restart" not in sys.argv:
            wait("Control Bus example tags", lambda: tool("setup") == "ready")
            ensure(NAME, (ROOT / "examples/camel/control-bus.yaml").read_text())
        wait("Control Bus example startup", lambda: running(NAME))
        # A previous manual suspension should not prevent an idempotent verification run.
        assert tool("command", "resume") == "written"
        wait("heartbeat starts", lambda: snapshot()["RouteState"]["value"].lower() == "started" and snapshot()["TickCount"]["value"] > 0)
        first = snapshot()
        wait("two-second heartbeat advances", lambda: snapshot()["TickCount"]["value"] > first["TickCount"]["value"], seconds=20)
        assert tool("command", "suspend") == "written"
        paused_by_test = True
        wait("Control Bus suspends heartbeat", lambda: snapshot()["RouteState"]["value"].lower() == "suspended")
        paused = snapshot()
        deadline = time.monotonic() + 4.5
        while time.monotonic() < deadline:
            report = snapshot()
            assert report["Heartbeat"]["value"] == paused["Heartbeat"]["value"], report
            assert report["TickCount"]["value"] == paused["TickCount"]["value"], report
            time.sleep(0.3)
        assert tool("command", "resume") == "written"
        wait("Control Bus resumes writes", lambda: snapshot()["RouteState"]["value"].lower() == "started"
            and snapshot()["Heartbeat"]["value"] != paused["Heartbeat"]["value"], seconds=20)
        paused_by_test = False
        assert tool("command", "status") == "written"
        details = api("GET", MODULE + "/camel-route-details?name=" + NAME)
        heartbeat = next(route for route in details["routes"] if route["id"] == "controlbus-heartbeat")
        assert heartbeat["state"] == "RUNNING" and heartbeat["failed"] == 0, heartbeat
        print("Control Bus example verified: periodic Good tag writes, suspended for >2 periods, resumed. Left running.")
        print(json.dumps(snapshot(), indent=2))
    finally:
        # Never leave our verification suspension active if an assertion fails.
        if paused_by_test and running(NAME):
            try:
                tool("command", "resume")
            except Exception:
                pass
        if own_tools:
            m["delete"](TOOLS)


if __name__ == "__main__":
    main()
