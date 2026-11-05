"""Opt-in 64-pattern factory simulation, restricted to the isolated 9288 Gateway.

Never run the ordinary smoke suite after deployment: it reinstalls the old demos.
"""
import argparse
import hashlib
import io
import json
import runpy
import subprocess
import urllib.parse
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "examples/camel/simulation"
g = runpy.run_path(str(ROOT / "scripts/camel-acceptance.py"))
projects = runpy.run_path(str(ROOT / "scripts/camel-example-project.py"))
api, wait = g["api"], g["wait"]
PREFIX = "com.noahburnette.ignition.apachecamel/"


def stage():
    inventory = api("GET", g["MODULE"] + "/camel-extensions")
    assert not inventory["startupError"], inventory["startupError"]
    existing = {f["name"]: f for f in inventory["files"]}
    jars = sorted((ROOT / "gateway/build/simulation-extensions").glob("*.jar"))
    assert jars, "Run :gateway:prepareSimulationExtensions first"
    token = api("GET", "/data/app/session")["csrfToken"]
    for jar in jars:
        data = jar.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if jar.name in existing:
            assert existing[jar.name]["sha256"] == digest, "Conflicting installed library: " + jar.name
            continue
        path = "/camel-extension-upload?confirm=true&name=" + urllib.parse.quote(jar.name)
        request = urllib.request.Request(g["BASE"] + g["MODULE"] + path, method="POST", data=data,
            headers={"Content-Type": "application/java-archive", "X-CSRF-Token": token})
        with g["HTTP"].open(request, timeout=60) as response:
            assert json.load(response)["restartRequired"]
        print("Staged " + jar.name)
    print("Restart the isolated Gateway to activate staged libraries.")


def backup(directory):
    directory.mkdir(parents=True, exist_ok=False)
    for item in api("GET", "/data/api/v1/projects/list")["items"]:
        archive = projects["export_project"](g, item["name"])
        (directory / (item["name"] + ".zip")).write_bytes(archive)
    for kind in ("camel-route", "camel-component", "camel-registry"):
        data = api("GET", "/data/api/v1/resources/list/" + PREFIX + kind)
        (directory / (kind + ".json")).write_text(json.dumps(data, indent=2))
    print("Backed up projects and Camel resources to " + str(directory))


def archive(project, libraries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        output.writestr("project.json", json.dumps({"title": project, "enabled": True, "inheritable": False, "parent": ""}))
        for name in libraries:
            path = "ignition/script-python/" + name + "/"
            output.writestr(path + "resource.json", json.dumps({"scope": "A", "version": 1,
                "restricted": False, "overridable": True, "files": ["code.py"], "attributes": {"hintScope": 1}}))
            output.writestr(path + "code.py", (ASSETS / (name + ".py")).read_bytes())
        if "eip_patterns" in libraries:
            path = "ignition/script-python/eip_manifest/"
            output.writestr(path + "resource.json", json.dumps({"scope": "A", "version": 1, "files": ["code.py"], "attributes": {"hintScope": 1}}))
            output.writestr(path + "code.py", "PATTERNS = " + repr(json.loads((ASSETS / "patterns.json").read_text())) + "\n")
            def resource(path, files, attributes=None):
                output.writestr(path + "/resource.json", json.dumps({"scope": "G", "version": 1,
                    "restricted": False, "overridable": True, "files": list(files), "attributes": attributes or {}}))
                for name, value in files.items():
                    output.writestr(path + "/" + name, value)
            for name, endpoint in [("startWorkOrder", "direct:start"), ("completeWorkOrder", "direct:complete")]:
                resource("ignition/message/" + name, {"handleMessage.py": 'def handleMessage(payload):\n\treturn system.camel.request("eip-work-orders", "' + endpoint + '", payload)\n'},
                    {"enabled": True, "threadType": "Dedicated"})
            resource("ignition/message/patternService", {"handleMessage.py": 'def handleMessage(payload):\n\treturn dict(payload, activated=True)\n'}, {"enabled": True, "threadType": "Dedicated"})
            resource("com.inductiveautomation.perspective/page-config", {"config.json": json.dumps({"pages": {"/": {"viewPath": "Factory", "title": "Camel EIP Factory", "docks": {}}}})})
            resource("com.inductiveautomation.perspective/views/Factory", {"view.json": json.dumps(view())})
    return buffer.getvalue()


def script_body(code):
    # Perspective stores the indented body; Ignition supplies the def wrapper.
    return "\n".join("\t" + line for line in code.rstrip().splitlines())


def binding(tag, code):
    return {"binding": {"type": "tag", "config": {"mode": "direct", "tagPath": "[default]EIPSimulation/" + tag},
        "transforms": [{"type": "script", "code": script_body(code)}]}}


def view():
    def component(kind, name, props, **extra):
        basis = props.get("style", {}).get("height", "46px" if kind.startswith("input.") else "36px")
        result = {"type": "ia." + kind, "meta": {"name": name}, "props": props,
                  "position": {"grow": 0, "shrink": 0, "basis": basis}}
        result.update(extra)
        return result
    def label(name, text, size=16):
        return component("display.label", name, {"text": text, "style": {"fontSize": size, "color": "#d9e6f2", "padding": "8px 0"}})
    snapshot = "Dashboard/Snapshot"
    header = label("Title", "CAMEL // FACTORY INTEGRATION LAB", 28)
    subtitle = label("Subtitle", "64 behavioral pattern checks · New work orders every 30 seconds · Native Ignition + persistent JMS + MQTT", 14)
    stats = label("Stats", "Waiting for factory snapshot", 20)
    stats["propConfig"] = {"props.text": binding(snapshot, 's = system.util.jsonDecode(value)\nt = s.get("totals", {})\nreturn "%s generated   /   %s available   /   %s completed" % (t.get("generated", 0), t.get("available", 0), t.get("completed", 0))')}
    orders = component("input.dropdown", "WorkOrder", {"options": [], "value": "", "placeholder": {"text": "Select an available work order"}, "search": {"enabled": True}, "style": {"minHeight": "44px"}})
    orders["propConfig"] = {"props.options": binding(snapshot, 'return system.util.jsonDecode(value).get("orders", [])')}
    machines = component("input.dropdown", "Machine", {"options": [{"value": name, "label": name} for name in ["press-01", "lathe-01", "packer-01"]], "value": "press-01", "style": {"minHeight": "44px"}})
    start = component("input.button", "Start", {"text": "Start selected work order", "style": {"backgroundColor": "#28c8a0", "color": "#081d24", "fontWeight": "bold", "minHeight": "46px"}})
    start["events"] = {"onActionPerformed": {"type": "script", "scope": "G", "config": {"script": '''try:
    order = self.getSibling("WorkOrder").props.value
    machine = self.getSibling("Machine").props.value
    result = system.camel.request("eip-work-orders", "direct:ui-start", {"id": order, "machine": machine})
    self.getSibling("Feedback").props.text = result["message"]
except Exception:
    self.getSibling("Feedback").props.text = "Start failed. Check Gateway logs; no alternate transport was used."
'''}}}
    table = component("display.table", "Machines", {"data": [], "selection": {"enableRowSelection": False}, "columns": [
        {"field": field, "header": {"title": title}} for field, title in [("machine", "Machine"), ("status", "Status"),
        ("workOrder", "Work order"), ("progress", "Produced"), ("quantity", "Target")]], "style": {"height": "210px"}, "pager": {"bottom": False}})
    table["propConfig"] = {"props.data": binding(snapshot, 'return system.util.jsonDecode(value).get("machines", [])')}
    push = label("LiveNotifications", "Waiting for a native Camel → Perspective notification", 13)
    push["scripts"] = {"messageHandlers": [{"messageType": "workOrdersChanged", "script": '\tself.props.text = "Native Camel → Perspective notification received at " + system.date.format(system.date.now(), "HH:mm:ss")',
        "sessionScope": True, "pageScope": False, "viewScope": False}]}
    run = component("input.button", "RunPatterns", {"text": "Run all 64 pattern checks", "style": {"minHeight": "44px"}})
    run["events"] = {"onActionPerformed": {"type": "script", "scope": "G", "config": {"script": 'system.camel.send("eip-patterns", "seda:pattern-suite?size=1&discardWhenFull=false&blockWhenFull=false", {})'}}}
    run["propConfig"] = {"props.enabled": binding("Tests/Running", "return not bool(value)")}
    # Perspective event configurations are grouped by event namespace.
    start["events"] = {"component": start["events"]}
    run["events"] = {"component": run["events"]}
    for button in [start, run]:
        action = button["events"]["component"]["onActionPerformed"]["config"]
        action["script"] = script_body(action["script"])
    status = label("TestStatus", "Not run", 18)
    status["propConfig"] = {"props.text": binding("Tests/LastRun", "return value")}
    coverage = component("display.table", "PatternCoverage", {"data": [], "columns": [
        {"field": "name", "header": {"title": "Pattern"}, "width": 230},
        {"field": "status", "header": {"title": "Result"}, "width": 90},
        {"field": "evidence", "header": {"title": "Observed behavior"}, "width": 850}],
        "style": {"height": "430px"}, "pager": {"bottom": True, "activeOption": 25}, "filter": {"enabled": True}})
    coverage["propConfig"] = {"props.data": binding("Tests/Coverage", "return system.util.jsonDecode(value)")}
    root = component("container.flex", "root", {"direction": "column", "style": {"backgroundColor": "#102332", "padding": "26px", "gap": "10px", "overflow": "auto"}})
    root["children"] = [header, subtitle, stats, orders, machines, start, label("Feedback", "Choose an order and a machine. Progress and completion are simulated automatically."),
        table, push, run, status, coverage, label("MQTT", "All [default] tag values → ignition/default/<encoded tag path> · MQTT QoS 1, retained · no delivery guarantee across Gateway downtime", 12)]
    return {"props": {"defaultSize": {"width": 1300, "height": 1200}}, "params": {}, "custom": {}, "root": root}


def ui():
    projects["import_archive"](g, "global", archive("global", ["eip_simulation", "eip_routes", "eip_patterns"]))
    print("Updated the global project and Perspective view through the native import API.")


def ready(name):
    report = api("GET", g["MODULE"] + "/camel-route-details?name=" + name)
    if report["state"] == "ERROR":
        raise RuntimeError("Route startup failed; stop and inspect Gateway logs: " + json.dumps(report))
    return report["state"] == "RUNNING"


def call(resource, operation, body=None):
    return api("POST", "/system/integrations/" + resource + "/" + operation, body or {})


def deploy(directory):
    current = api("GET", g["MODULE"] + "/camel-routes")
    assert not {"eip-work-orders", "eip-default-mqtt", "eip-patterns"}.intersection(current), "Simulation already installed; export Designer edits and use the explicit ui update instead"
    # Back up before importing/replacing scripts or touching any existing routes.
    backup(directory)
    quiesce(directory)
    install()


def quiesce(directory):
    """Stop existing subscribers before publishing a full-provider MQTT snapshot.

    An old '#' -> tag mirror would otherwise feed publications back into default.
    Keep the original exports untouched and record our intentional metadata change
    separately so cleanup can still detect concurrent edits.
    """
    old = json.loads((directory / "camel-route.json").read_text())["items"]
    stopped = {}
    for item in old:
        path = "/data/api/v1/resources/find/" + PREFIX + "camel-route/" + item["name"]
        current = api("GET", path)
        assert current["signature"] == item["signature"], "Route changed since backup: " + item["name"]
        if current.get("enabled", True):
            api("PUT", "/data/api/v1/resources/" + PREFIX + "camel-route", [{
                "name": item["name"], "signature": current["signature"], "enabled": False}])
            current = api("GET", path)
        stopped[item["name"]] = current["signature"]
        (directory / "quiesced-routes.json").write_text(json.dumps(stopped, indent=2))
    def stopped_all():
        states = api("GET", g["MODULE"] + "/camel-routes")
        return all(states.get(item["name"]) == "DISABLED" for item in old)
    wait("existing routes stopped before MQTT publication", stopped_all)


def install():
    inventory = api("GET", g["MODULE"] + "/camel-extensions")
    assert not inventory["startupError"], inventory["startupError"]
    assert all(any(f["name"] == name and f["state"] == "ACTIVE" for f in inventory["files"])
               for name in ["camel-jms-4.22.2.jar", "camel-resilience4j-4.22.2.jar", "artemis-jakarta-client-2.44.0.jar"])
    projects["import_archive"](g, "global", archive("global", ["eip_simulation", "eip_routes", "eip_patterns"]))
    setup_yaml = """- route:
    from:
      uri: ignition-gateway-http:/setup
      steps:
        - to: ignition-script:eip_simulation.setup?project=global
"""
    save("camel-route", "eip-install", {"yaml": setup_yaml})
    wait("explicit setup endpoint", lambda: ready("eip-install"))
    wait("global project scripts and explicit schema setup", lambda: call("eip-install", "setup")["ready"], seconds=40)
    # Persist a native encrypted secret; the bean arguments retain only a reference.
    token = api("GET", "/data/app/session")["csrfToken"]
    request = urllib.request.Request(g["BASE"] + "/data/api/v1/encryption/encrypt", method="POST",
        data=b"simulation-local-only", headers={"Content-Type": "text/plain", "X-CSRF-Token": token})
    with g["HTTP"].open(request, timeout=15) as response:
        ciphertext = json.load(response)
    providers = api("GET", "/data/api/v1/resources/list/ignition/secret-provider")["items"]
    if not any(p["name"] == "eip-simulation-secrets" for p in providers):
        api("POST", "/data/api/v1/resources/ignition/secret-provider", [{"name": "eip-simulation-secrets", "config": {
            "profile": {"type": "internal"}, "settings": {"secrets": {"broker": {"ciphertext": ciphertext}}}}}])
    save("camel-registry", "eip-jms-factory", {"type": "BEAN", "bean": {"project": "global", "functionPath": "eip_simulation.jmsFactory",
        "arguments": {"url": "tcp://artemis:61616", "username": "simulation", "password": "{{secret.brokerPassword}}"}, "closeOnStop": True}})
    save("camel-component", "simulation-mqtt", {"type": "MQTT5", "mqtt": {"brokerUrl": "tcp://mqtt:1883", "qos": 1, "cleanStart": True, "sessionExpiryInterval": 0, "connectionTimeout": 5}})
    secrets = {"brokerPassword": {"providerName": "eip-simulation-secrets", "secretName": "broker"}}
    for name, function, registry, components in [
        ("eip-work-orders", "eip_routes.configure", ["eip-jms-factory"], []),
        ("eip-default-mqtt", "eip_routes.mqtt", [], ["simulation-mqtt"]),
        ("eip-patterns", "eip_patterns.configure", ["eip-jms-factory"], [])]:
        save("camel-route", name, {"language": "JYTHON", "project": "global", "functionPath": function,
            "registry": registry, "components": components, "secrets": secrets if registry else {}})
        wait(name + " startup", lambda name=name: ready(name), seconds=40)
    print("New simulation running alongside old demos. Verify it before cleanup.")
    print("UI: http://localhost:9288/data/perspective/client/global")


def cleanup(directory):
    assert directory.is_dir(), "Existing backup directory required"
    for name in ["eip-work-orders", "eip-default-mqtt", "eip-patterns"]:
        assert ready(name)
    report = call("eip-work-orders", "coverage")
    assert report["passed"] == 64 and not report["running"], "Do not remove old demos until every new pattern passes"
    quiesced_path = directory / "quiesced-routes.json"
    quiesced = json.loads(quiesced_path.read_text()) if quiesced_path.exists() else {}
    for kind in ["camel-route", "camel-component", "camel-registry"]:
        old = json.loads((directory / (kind + ".json")).read_text())["items"]
        for item in old:
            current = api("GET", "/data/api/v1/resources/find/" + PREFIX + kind + "/" + item["name"])
            expected = quiesced.get(item["name"], item["signature"]) if kind == "camel-route" else item["signature"]
            assert current["signature"] == expected, "Resource changed since backup: " + item["name"]
            api("POST", "/data/api/v1/resources/delete/" + PREFIX + kind + "?confirm=true",
                [{"name": item["name"], "signature": current["signature"]}])
    # The installer is not an ongoing configuration surface.
    g["delete"]("eip-install")
    existing = api("GET", "/data/api/v1/projects/list")["items"]
    # Children first, preserving a valid inheritance graph throughout deletion.
    pending = {item["name"]: item for item in existing if item["name"] != "global"}
    while pending:
        leaves = [name for name in pending if not any(item.get("parent") == name for item in pending.values())]
        assert leaves, "Unexpected project inheritance cycle"
        for name in leaves:
            assert (directory / (name + ".zip")).exists(), "Project was not backed up: " + name
            before = (directory / (name + ".zip")).read_bytes()
            after = projects["export_project"](g, name)
            with zipfile.ZipFile(io.BytesIO(before)) as a, zipfile.ZipFile(io.BytesIO(after)) as b:
                assert {n: a.read(n) for n in a.namelist()} == {n: b.read(n) for n in b.namelist()}, "Project changed since backup: " + name
            api("DELETE", "/data/api/v1/projects/" + name)
            del pending[name]
    assert [p["name"] for p in api("GET", "/data/api/v1/projects/list")["items"]] == ["global"]
    print("Removed backed-up demo routes/components/registries and old projects; only global remains.")


def verify():
    for name in ["eip-work-orders", "eip-default-mqtt", "eip-patterns"]:
        wait(name, lambda name=name: ready(name))
    prepared = call("eip-patterns", "prepare-persistence")
    subprocess.run(["docker", "compose", "-f", str(ROOT / "docker/camel-acceptance.yml"), "-f", str(ROOT / "docker/camel-simulation.yml"), "restart", "artemis"], check=True, timeout=30)
    def recovered():
        try:
            return call("eip-patterns", "finish-persistence")
        except urllib.error.HTTPError:
            return False
    proof = wait("persistent message after broker restart", recovered, seconds=45)
    assert proof["token"] == prepared["token"]
    before = call("eip-work-orders", "snapshot")
    assert call("eip-patterns", "run")["accepted"]
    wait("behavioral suite to start", lambda: call("eip-work-orders", "coverage")["running"])
    report = wait("64 behavioral pattern assertions", lambda: (r := call("eip-work-orders", "coverage")) and not r["running"] and r, seconds=180)
    print(json.dumps(report, indent=2))
    assert len(report["patterns"]) == 64 and report["passed"] == 64, "Pattern failures must be fixed, not skipped or emulated"
    # Exercise the real work-order request path and exclusive machine reservation.
    assert before["orders"], "Generator has not produced an available order"
    identifier = before["orders"][0]["value"]
    idle = next((machine for machine in before["machines"] if machine["status"] == "IDLE"), None)
    assert idle, "Need one idle simulation machine for acceptance"
    result = call("eip-work-orders", "start", {"id": identifier, "machine": idle["machine"]})
    assert result["ok"], result
    assert not call("eip-work-orders", "start", {"id": identifier, "machine": idle["machine"]})["ok"]
    wait("tag-triggered automatic completion", lambda: not any(machine["workOrder"] == identifier for machine in call("eip-work-orders", "snapshot")["machines"]), seconds=40)
    assert not call("eip-work-orders", "complete", {"id": identifier, "machine": idle["machine"]})["ok"]
    baseline = call("eip-work-orders", "snapshot")["totals"]["generated"]
    wait("next 30-second work order", lambda: call("eip-work-orders", "snapshot")["totals"]["generated"] > baseline, seconds=40)
    print("64 patterns, persistent broker restart, machine exclusivity, native completion and 30-second generation verified.")


def mqtt_audit():
    inventory = call("eip-work-orders", "tag-inventory")
    result = subprocess.run(["docker", "compose", "-f", str(ROOT / "docker/camel-acceptance.yml"), "exec", "-T", "mqtt",
        "mosquitto_sub", "-V", "mqttv5", "-h", "localhost", "-t", "ignition/default/#", "-F", "%r\t%p", "-W", "10"], capture_output=True, text=True, timeout=25)
    assert result.returncode in [0, 27], result.stderr
    paths = set()
    for line in result.stdout.splitlines():
        retained, payload = line.split("\t", 1)
        if retained != "1":
            continue
        frame = json.loads(payload)
        assert {"tagPath", "value", "quality", "timestamp"}.issubset(frame), frame
        paths.add(frame["tagPath"])
    expected = set(inventory["pathHashes"])
    for offset in range(256, inventory["count"], 256):
        page = call("eip-work-orders", "tag-inventory", {"offset": offset})
        assert page["pathDigest"] == inventory["pathDigest"], "Tag inventory changed during audit; retry"
        expected.update(page["pathHashes"])
    published = {hashlib.sha256(path.encode()).hexdigest() for path in paths}
    # Native subscriptions can also emit folder/UDT quality events. Retained
    # records for deleted tags are historical, not a missing current tag.
    assert not expected - published, {"defaultAtomicTags": inventory["count"], "retainedTagPaths": len(paths), "missingAtomicTags": len(expected - published)}
    details = api("GET", g["MODULE"] + "/camel-route-details?name=eip-default-mqtt")
    assert details["diagnostics"]["rejected"] == 0 and details["diagnostics"]["failed"] == 0, details["diagnostics"]
    print("Every default atomic tag has a retained MQTT frame: %d/%d current atomic paths matched by SHA-256 (%d total retained paths, including folder/UDT/deleted-tag quality events); no native rejections/failures." % (len(expected), inventory["count"], len(paths)))


def save(kind, name, config):
    if kind == "camel-route":
        config = {"httpSecurityLevels": ["Authenticated/Roles/Administrator"], **config}
    type_path = PREFIX + kind
    items = api("GET", "/data/api/v1/resources/list/" + type_path)["items"]
    existing = next((item for item in items if item["name"] == name), None)
    row = {"name": name, "enabled": True, "config": config}
    if existing:
        row["signature"] = existing["signature"]
    api("PUT" if existing else "POST", "/data/api/v1/resources/" + type_path, [row])


def preflight():
    project, route = "eip_preflight", "eip-preflight"
    projects["import_archive"](g, project, archive(project, ["eip_preflight"]))
    save("camel-route", route, {"language": "JYTHON", "project": project, "functionPath": "eip_preflight.configure"})
    def ready():
        report = api("GET", g["MODULE"] + "/camel-route-details?name=" + route)
        if report["state"] == "ERROR":
            raise RuntimeError("Preflight route failed; inspect Gateway logs before proceeding: " + json.dumps(report))
        return report["state"] == "RUNNING"
    wait("real JMS transaction and circuit-breaker preflight", ready, seconds=40)
    result = api("POST", "/system/integrations/" + route + "/probe", {})
    assert result["transaction"] == "committed" and result["rollback"] and result["perspective"], result
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["stage", "backup", "preflight", "deploy", "install", "ui", "cleanup", "verify", "mqtt_audit"])
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args()
    assert g["BASE"] == "http://localhost:9288"
    wait("isolated Gateway login", g["bootstrap"], seconds=180)
    if args.action in ["backup", "deploy", "cleanup"]:
        assert args.backup_dir, "--backup-dir is required"
        globals()[args.action](args.backup_dir)
    else:
        globals()[args.action]()


if __name__ == "__main__":
    main()
