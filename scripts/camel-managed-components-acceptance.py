"""Real HTTP/MQTT/Quartz/file removal and replacement on the isolated Gateway.

Run --remove, restart, --verify-removed, --restore, restart, --verify-restored.
Never edits Gateway resource directories; restore bytes come from the built module.
"""
import json
from pathlib import Path
import runpy
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]
ARTIFACTS = ("camel-http", "camel-paho-mqtt5", "camel-quartz", "camel-file")
SCHEMES = {"http", "https", "paho-mqtt5", "quartz", "file"}
PROOF = "camel-managed-removal-proof"


def schemes():
    return {item["component"]["scheme"] for item in api("GET", MODULE + "/camel-component-metadata")["components"]}


def native_proof():
    assert api("POST", "/system/integrations/" + PROOF + "/echo", {}) == "native-bridge-still-running"


def remove():
    config = {"yaml": """- route:
    id: native-removal-proof
    from:
      uri: ignition-gateway-http:/echo?httpMethodRestrict=POST
      steps:
        - setBody:
            constant: native-bridge-still-running
"""}
    kind = m["TYPE"]
    config["httpSecurityLevels"] = ["Authenticated/Roles/Administrator"]
    try:
        previous = api("GET", "/data/api/v1/resources/find/" + kind + "/" + PROOF)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        api("POST", "/data/api/v1/resources/" + kind, [{"name": PROOF, "enabled": True, "config": config}])
    else:
        api("PUT", "/data/api/v1/resources/" + kind, [{"name": PROOF, "signature": previous["signature"], "enabled": True, "config": config}])
    wait("native removal proof route", lambda: api("GET", MODULE + "/camel-routes").get(PROOF) == "RUNNING")
    native_proof()
    files = api("GET", MODULE + "/camel-extensions")["files"]
    for artifact in ARTIFACTS:
        library = next(file for file in files if file["name"] == artifact + "-4.22.2.jar")
        plan = api("GET", MODULE + "/camel-extension-removal-preview?" + urllib.parse.urlencode({"id": library["id"], "includeUnused": "false"}))
        api("POST", MODULE + "/camel-extension-remove?" + urllib.parse.urlencode({"confirm": "true", "id": library["id"], "includeUnused": "false", "token": plan["token"]}))
    assert SCHEMES <= schemes(), "Removal must not change the live loader before restart"
    print("HTTP/MQTT/Quartz/file removals staged through the protected API; current loader unchanged")


def verify_removed():
    assert not (SCHEMES & schemes()), "Removed schemes are still exposed"
    states = api("GET", MODULE + "/camel-routes")
    assert states[PROOF] == "RUNNING", states
    assert states["official-cron-upload-proof"] == "ERROR", states
    native_proof()
    print("After restart: HTTP/MQTT/Quartz/file unavailable, dependent Cron is ERROR, native Ignition HTTP remains RUNNING")


def restore():
    verify_removed()
    root = Path(__file__).resolve().parents[1]
    bundle = next(path for path in (root / "gateway/build/libs").glob("*.jar") if not path.name.endswith("-all.jar"))
    token = api("GET", "/data/app/session")["csrfToken"]
    with zipfile.ZipFile(bundle) as archive:
        for artifact in ARTIFACTS:
            name = artifact + "-4.22.2.jar"
            data = archive.read("camel-extensions/" + name + ".bin")
            request = urllib.request.Request(m["BASE"] + MODULE + "/camel-extension-upload?confirm=true&name=" + name,
                method="POST", data=data, headers={"Content-Type": "application/java-archive", "X-CSRF-Token": token})
            with m["HTTP"].open(request, timeout=30) as response:
                assert json.load(response)["restartRequired"]
    assert not (SCHEMES & schemes()), "Replacement must not change the live loader before restart"
    print("HTTP/MQTT/Quartz/file replacements uploaded; activation remains restart-only")


def verify_restored():
    assert SCHEMES <= schemes(), "Restored schemes missing"
    states = api("GET", MODULE + "/camel-routes")
    assert states["official-cron-upload-proof"] == "RUNNING", states
    assert states["camel-acceptance-sections"] == "RUNNING", states
    native_proof()
    print("After replacement restart: all managed types and dependent routes recovered; native bridge remains RUNNING")


if __name__ == "__main__":
    wait("isolated Gateway login", m["bootstrap"])
    actions = {"--remove": remove, "--verify-removed": verify_removed, "--restore": restore, "--verify-restored": verify_restored}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        raise SystemExit("Use --remove, --verify-removed, --restore or --verify-restored")
    actions[sys.argv[1]]()
