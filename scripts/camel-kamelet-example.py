"""Native YAML Kamelet example on port 9288; never edits resource files.

--install adds missing native definitions, project code, tags and routes, then verifies delivery.
--verify checks the installed example, including after a Gateway restart.
"""
import argparse
import io
import json
from pathlib import Path
import runpy
import urllib.error
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PROJECT = "CamelExamples"
DEMO = "camel-example-kamelet-temperature"
TOOLS = "camel-example-kamelet-controls"
SETUP = "camel-example-kamelet-setup"
DEFINITION = "normalize-temperature-action.kamelet.yaml"
KAMELET_TYPE = "com.noahburnette.ignition.apachecamel/camel-kamelet"
KAMELET = "normalize-temperature-action"
BASE_PATH = "/system/integrations/demo/kamelets"


def require_active(gateway):
    data = gateway["api"]("GET", gateway["MODULE"] + "/camel-extensions")
    assert not data.get("startupError"), data.get("startupError")
    rows = [row for row in data["files"] if row["name"] == "camel-kamelet-4.22.2.jar"]
    assert len(rows) == 1 and rows[0]["state"] == "ACTIVE", "Included camel-kamelet library is unavailable"


def ensure_project(gateway, name="kamelets"):
    helper = runpy.run_path(str(ROOT / "scripts/camel-example-project.py"))
    archive = helper["export_project"](gateway, PROJECT)
    library = {
        "resource.json": json.dumps({"scope": "A", "version": 1, "restricted": False,
            "overridable": True, "files": ["code.py"], "attributes": {"hintScope": 1}}).encode(),
        "code.py": (ROOT / "examples/camel/project" / (name + ".py")).read_bytes(),
    }
    if archive is not None:
        with zipfile.ZipFile(io.BytesIO(archive)) as current:
            prefix = "ignition/script-python/" + name + "/"
            if any(name.startswith(prefix) for name in current.namelist()):
                # Ignition reformats resource.json on export. Compare code and decoded
                # semantics, not its JSON whitespace, and leave all metadata untouched.
                if (prefix + "code.py" not in current.namelist()
                        or current.read(prefix + "code.py") != library["code.py"]
                        or prefix + "resource.json" not in current.namelist()):
                    raise ValueError("Existing kamelets script conflicts; refusing to overwrite")
                metadata = json.loads(current.read(prefix + "resource.json"))
                if metadata.get("scope") not in ("A", "G") or "code.py" not in metadata.get("files", []):
                    raise ValueError("Existing kamelets script is not available in Gateway scope")
                return
    # Missing libraries may be added, but no existing project files are replaced.
    merged = helper["merge_libraries"](archive, {name: library})
    helper["import_archive"](gateway, PROJECT, merged)


def control_yaml(function, path):
    return """- route:
    from:
      uri: ignition-gateway-http:%s?httpMethodRestrict=POST
      steps:
        - to: ignition-script:kamelets.%s?project=CamelExamples
""" % (path, function)


def ensure_definition(gateway, name, config):
    try:
        current = gateway["api"]("GET", "/data/api/v1/resources/find/" + KAMELET_TYPE + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        current = None
    if current is not None:
        if not current["enabled"] or any(current["config"].get(key) != value for key, value in config.items()):
            raise ValueError("Existing Kamelet differs; refusing to overwrite: " + name)
    else:
        gateway["api"]("POST", "/data/api/v1/resources/" + KAMELET_TYPE,
            [{"name": name, "enabled": True, "config": config}])


def ensure_route(gateway, name, source, http=False, selected_kamelets=None):
    grants = ["Authenticated/Roles/Administrator"] if http else []
    selected_kamelets = selected_kamelets or []
    existing = gateway["find"](name)
    if existing:
        config = existing["config"]
        if (config.get("yaml") != source or config.get("language") != "YAML"
                or config.get("httpSecurityLevels") != grants or config.get("components")
                or config.get("registry") or config.get("properties") or config.get("secrets")
                or not existing["enabled"] or config.get("kamelets", []) != selected_kamelets):
            raise ValueError("Existing example route differs; refusing to overwrite: " + name)
    else:
        gateway["api"]("POST", "/data/api/v1/resources/" + gateway["TYPE"], [{
            "name": name, "type": gateway["TYPE"], "enabled": True,
            "config": {"language": "YAML", "yaml": source, "httpSecurityLevels": grants, "kamelets": selected_kamelets}}])
    gateway["wait"]("running " + name,
        lambda: gateway["api"]("GET", gateway["MODULE"] + "/camel-routes").get(name) == "RUNNING", seconds=30)


def install(gateway):
    require_active(gateway)
    ensure_project(gateway)
    ensure_definition(gateway, KAMELET, {"language": "YAML", "yaml": (ROOT / "examples/camel/kamelets" / DEFINITION).read_text(), "kamelets": []})
    # The temporary setup endpoint has only the narrow, create-missing demo operation.
    # Delete only if this invocation created it; never take ownership of an old resource.
    assert gateway["find"](SETUP) is None, "Setup route already exists; inspect before proceeding"
    created = False
    try:
        created = True
        ensure_route(gateway, SETUP, control_yaml("setup", "/demo/kamelets/setup"), http=True)
        gateway["wait"]("demo project ready and tags created",
            lambda: gateway["api"]("POST", BASE_PATH + "/setup", {}).get("LatestReading", {}).get("good"), seconds=30)
    finally:
        if created:
            gateway["delete"](SETUP)
    controls = control_yaml("input", "/demo/kamelets/input") + control_yaml("inspect", "/demo/kamelets/status")
    ensure_route(gateway, TOOLS, controls, http=True)
    ensure_route(gateway, DEMO, (ROOT / "examples/camel/kamelet-temperature.yaml").read_text(), selected_kamelets=[KAMELET])


def verify(gateway):
    require_active(gateway)
    for name in (DEMO, TOOLS):
        gateway["wait"]("running " + name,
            lambda: gateway["api"]("GET", gateway["MODULE"] + "/camel-routes").get(name) == "RUNNING", seconds=30)

    def status():
        values = gateway["api"]("POST", BASE_PATH + "/status", {})
        assert all(row["good"] for row in values.values()), values
        return values

    def reading():
        value = status()["LatestReading"]["value"]
        if value == "Waiting for a reading":
            return None
        return json.loads(value)

    def details():
        return gateway["api"]("GET", gateway["MODULE"] + "/camel-route-details?name=" + DEMO)

    def submit(unit, value, expected):
        gateway["api"]("POST", BASE_PATH + "/input", {"unit": unit, "value": value})
        gateway["wait"]("normalized " + unit + " reading " + str(value), lambda: reading() == expected, seconds=15)

    def expected(machine, value, state="NORMAL"):
        return {"machine": machine, "temperatureC": value, "status": state}

    # Force distinct values first: no reliance on same-value writes generating changes.
    submit("C", -10, expected("Press01", -10))
    submit("C", 30, expected("Press01", 30))
    submit("F", 68, expected("Press02", 20))
    submit("F", 86, expected("Press02", 30))
    print("30 C and 86 F both normalize to 30 C, using two Kamelet instances.")
    before = details()["metrics"]["failed"]
    previous = reading()
    gateway["api"]("POST", BASE_PATH + "/input", {"unit": "C", "value": 1000})
    gateway["wait"]("invalid temperature rejected", lambda: details()["metrics"]["failed"] > before, seconds=15)
    assert reading() == previous, "Invalid input overwrote the last valid reading"
    submit("C", 30, expected("Press01", 30))
    submit("F", 194, expected("Press02", 90, "HIGH"))
    live = details()
    route_ids = {row["id"] for row in live["routes"]}
    assert {"press01-normalizer", "press02-normalizer"}.issubset(route_ids), route_ids
    print("Invalid input rejected, valid input recovers, and 194 F becomes 90 C / HIGH.")
    print("Gateway: http://localhost:9288; route:", DEMO)
    print("Tags: [default]CamelExamples/Kamelets/{CelsiusInput,FahrenheitInput,LatestReading}")
    print("LatestReading:", json.dumps(reading()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--install", action="store_true")
    modes.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    gateway = runpy.run_path(str(ROOT / "scripts/camel-acceptance.py"))
    assert gateway["BASE"] == "http://localhost:9288", "Only the isolated Gateway is supported"
    gateway["wait"]("isolated Gateway login", gateway["bootstrap"], seconds=90)
    require_active(gateway)
    if args.install:
        install(gateway)
    verify(gateway)


if __name__ == "__main__":
    main()
