"""Dependency ownership, transitive impact and stale-plan refusal on the isolated Gateway."""
import runpy
import time
import urllib.error
import urllib.parse
from pathlib import Path

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
MODULE = m["MODULE"]


def preview(id, cleanup=False):
    return api("GET", MODULE + "/camel-extension-removal-preview?" + urllib.parse.urlencode({"id": id, "includeUnused": str(cleanup).lower()}))


def expect_stale(id, token):
    try:
        api("POST", MODULE + "/camel-extension-remove?" + urllib.parse.urlencode({"id": id, "confirm": "true", "includeUnused": "false", "token": token}))
    except urllib.error.HTTPError as error:
        assert error.code == 409, (error.code, error.read())
    else:
        raise AssertionError("A stale or incorrect removal token was accepted")


def main():
    wait("isolated Gateway login", m["bootstrap"])
    inventory = api("GET", MODULE + "/camel-extensions")
    assert len(inventory["groups"]) >= 16
    quartz = next(file for file in inventory["files"] if file["name"] == "camel-quartz-4.22.2.jar")
    mqtt = next(file for file in inventory["files"] if file["name"] == "camel-paho-mqtt5-4.22.2.jar")
    quartz_plan = preview(quartz["id"])
    assert "Cron" in quartz_plan["dependentGroups"], quartz_plan
    assert any(route["name"] == "official-cron-upload-proof" for route in quartz_plan["routes"]), quartz_plan
    assert any(component["name"] == "proof-clock" for component in quartz_plan["components"]), quartz_plan
    mqtt_plan = preview(mqtt["id"])
    assert any(route["name"] == "camel-acceptance-sections" for route in mqtt_plan["routes"]), mqtt_plan
    shared = next(file for file in inventory["files"] if file["name"] == "quartz-2.5.2.jar")
    assert {"Cron", "Quartz"} <= set(inventory["usage"][shared["id"]])
    expect_stale(mqtt["id"], "wrong-token")

    # Change a harmless disabled route definition between preview and confirmation.
    name, kind = "camel-impact-preview-proof", m["TYPE"]
    config = {"yaml": """- route:
    from:
      uri: ignition-gateway-http:/preview?httpMethodRestrict=POST
      steps:
        - setBody:
            constant: impact-preview-proof
"""}
    config["yaml"] += "\n# impact revision " + str(time.time_ns()) + "\n"
    try:
        resource = api("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        api("POST", "/data/api/v1/resources/" + kind, [{"name": name, "enabled": False, "config": config}])
    else:
        api("PUT", "/data/api/v1/resources/" + kind, [{"name": name, "signature": resource["signature"], "enabled": False, "config": config}])
    wait("impact revision change", lambda: preview(mqtt["id"])["token"] != mqtt_plan["token"])
    expect_stale(mqtt["id"], mqtt_plan["token"])
    after = api("GET", MODULE + "/camel-extensions")
    assert {file["id"]: file["state"] for file in after["files"]} == {file["id"]: file["state"] for file in inventory["files"]}, after
    print("Dependency groups/shared ownership, Quartz -> Cron impact, configured/route references and stale-plan refusal verified; no libraries removed")


if __name__ == "__main__":
    main()
