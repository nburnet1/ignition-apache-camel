"""Upload the unmodified Maven Central camel-cron JAR; verify real scheduled delivery.

python3 scripts/camel-cron-acceptance.py --stage
docker compose -f docker/camel-acceptance.yml restart gateway
python3 scripts/camel-cron-acceptance.py --verify
"""
import hashlib
import json
import runpy
import sys
import urllib.error
import urllib.request

from pathlib import Path

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]
NAME = "camel-cron-4.22.2.jar"
SHA256 = "ffa40cb215e02f65243992ed4f14596833d740f60ed71102f6daf27b1fbefaea"
RESOURCE = "official-cron-upload-proof"
COMPONENT = "uploaded-cron"
PREFIX = "com.noahburnette.ignition.apachecamel/"


def stage():
    with urllib.request.urlopen("https://repo.maven.apache.org/maven2/org/apache/camel/camel-cron/4.22.2/" + NAME, timeout=30) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    assert hashlib.sha256(data).hexdigest() == SHA256, "Official Cron artifact checksum mismatch"
    files = api("GET", MODULE + "/camel-extensions")["files"]
    if not any(file["name"] == NAME and file["state"] != "REMOVAL_PENDING" for file in files):
        token = api("GET", "/data/app/session")["csrfToken"]
        request = urllib.request.Request(m["BASE"] + MODULE + "/camel-extension-upload?confirm=true&name=" + NAME,
            method="POST", data=data, headers={"Content-Type": "application/java-archive", "X-CSRF-Token": token})
        with m["HTTP"].open(request, timeout=30) as response:
            assert json.load(response)["restartRequired"]
    print("Official camel-cron 4.22.2 JAR staged; SHA-256 verified. Restart the isolated Gateway before --verify.")


def save(kind, name, config):
    kind = PREFIX + kind
    try:
        existing = api("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        api("POST", "/data/api/v1/resources/" + kind, [{"name": name, "enabled": True, "config": config}])
    else:
        api("PUT", "/data/api/v1/resources/" + kind, [{"name": name, "signature": existing["signature"], "enabled": True, "config": config}])


def verify():
    inventory = api("GET", MODULE + "/camel-extensions")
    assert not inventory["startupError"], inventory["startupError"]
    assert any(file["name"] == NAME and file["state"] == "ACTIVE" for file in inventory["files"])
    schema = next(item for item in api("GET", MODULE + "/camel-component-metadata")["components"] if item["component"]["scheme"] == "cron")
    assert schema["component"]["artifactId"] == "camel-cron"
    assert "cronService" in schema["componentProperties"]
    save("camel-component", COMPONENT, {"type": "GENERIC", "generic": {"scheme": "cron", "options": {"cronService": "quartz"}}})
    save("camel-route", RESOURCE, {"components": [COMPONENT], "yaml": """- route:
    id: official-cron-upload-proof
    from:
      uri: uploaded-cron:proof?schedule=0/2+*+*+*+*+?
      steps:
        - setBody:
            constant: OFFICIAL_CRON_UPLOAD_PROOF
        - to: log:official-cron-upload-proof?showBody=true
"""})

    def snapshot():
        value = api("GET", MODULE + "/camel-route-details?name=" + RESOURCE)
        assert value["state"] != "ERROR", value
        return value

    first = wait("official Cron route start", lambda: (value := snapshot())["state"] == "RUNNING" and value, seconds=30)
    baseline = first["routes"][0]["completed"]
    details = wait("three new Cron-triggered deliveries", lambda: (value := snapshot())["routes"][0]["completed"] >= baseline + 3 and value, seconds=30)
    route = details["routes"][0]
    assert route["failed"] == 0, route
    assert route["sourceComponent"] == "uploaded-cron", route
    print(json.dumps({"artifact": NAME, "sha256": SHA256, "resource": RESOURCE, "state": details["state"],
        "source": route["sourceComponent"], "completed": route["completed"], "newDeliveries": route["completed"] - baseline, "failed": route["failed"]}, indent=2))


if __name__ == "__main__":
    wait("isolated Gateway login", m["bootstrap"])
    if "--stage" in sys.argv:
        stage()
    elif "--verify" in sys.argv:
        verify()
    else:
        raise SystemExit("Specify --stage or --verify; this script does not restart the Gateway itself")
