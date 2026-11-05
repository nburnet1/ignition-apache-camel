"""Camel endpoint and resource-lifecycle acceptance against the isolated dev Gateway."""
import http.cookiejar
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
import urllib.parse
import zipfile
import subprocess
from pathlib import Path
from typing import assert_never

BASE = "http://localhost:9288"
TYPE = "com.noahburnette.ignition.apachecamel/camel-route"
MODULE = "/data/apache-camel"
CSRF = None
COOKIES = http.cookiejar.CookieJar()
HTTP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(COOKIES))


def api(method, path, body=None):
    # Test/demo provisioning convenience only: write explicit, unique consumer
    # URIs into fixture YAML. The module does not infer paths from resource names.
    if method in ("POST", "PUT") and path == "/data/api/v1/resources/" + TYPE and isinstance(body, list):
        body = [dict(row) for row in body]
        for row in body:
            for field in ("config", "backupConfig"):
                config = row.get(field)
                if isinstance(config, dict) and isinstance(config.get("yaml"), str):
                    row[field] = {**config, "yaml": fixture_http_yaml(row["name"], config["yaml"])}
    headers = {"Content-Type": "application/json"}
    if CSRF:
        headers["X-CSRF-Token"] = CSRF
    request = urllib.request.Request(
        BASE + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    with HTTP.open(request, timeout=15) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def fixture_http_yaml(name, source):
    """Namespace single-segment fixture consumers; explicit nested paths are unchanged."""
    return re.sub(r"ignition-gateway-http:/([A-Za-z0-9._-]+)(?=[?\s\"']|$)",
                  lambda match: "ignition-gateway-http:/" + name + "/" + match.group(1), source)


def bootstrap():
    """Normal built-in IdP login with session cookies and CSRF protection.

    This is intentionally restricted to the disposable Gateway at port 9288.
    No resource files are seeded and credentials/session tokens are never printed.
    """
    global CSRF
    with HTTP.open(BASE + "/data/app/login", timeout=15) as response:
        login = urllib.parse.urlsplit(response.geturl())
        response.read()
    if login.path != "/idp/default/authn/login":
        return False
    query = dict(urllib.parse.parse_qsl(login.query))
    challenge = api("POST", "/idp/default/authn/next-challenge", {"token": query["token"]})
    result = api("POST", "/idp/default/authn/submit-challenge/basic", {
        "token": challenge["token"], "rememberMe": False,
        "challenge": {"username": "admin", "password": "password"},
    })
    assert result.get("success"), "Isolated Gateway admin login failed"
    complete = api("POST", "/idp/default/authn/next-challenge", {"token": result["token"]})
    assert complete.get("complete"), "Unexpected additional login challenge"
    query["token"] = complete["token"]
    with HTTP.open(BASE + "/idp/default/oidc/auth?" + urllib.parse.urlencode(query), timeout=15) as response:
        response.read()
    session = api("GET", "/data/app/session")
    CSRF = session["csrfToken"]
    return True


def wait(description, predicate, seconds=120):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last = str(error)
            if isinstance(error, urllib.error.HTTPError):
                last += ": " + error.read().decode(errors="replace")[:500]
        time.sleep(1)
    raise AssertionError("Timed out waiting for %s: %s" % (description, last))


def find(name):
    try:
        return api("GET", "/data/api/v1/resources/find/" + TYPE + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def create(name, yaml, enabled=True, language="YAML", project="camel_acceptance"):
    config = {"language": "JYTHON", "project": project, "functionPath": yaml} if language == "JYTHON" else {"yaml": yaml}
    config["httpSecurityLevels"] = ["Authenticated/Roles/Administrator"]
    api("POST", "/data/api/v1/resources/" + TYPE, [{
        "name": name, "type": TYPE, "enabled": enabled, "config": config,
    }])


def delete(name):
    existing = find(name)
    if existing:
        api("POST", "/data/api/v1/resources/delete/" + TYPE + "?confirm=true", [{
            "name": name, "signature": existing["signature"],
        }])
        wait("deletion of " + name, lambda: name not in api("GET", MODULE + "/camel-routes"))


def diagnostics(name):
    return api("GET", MODULE + "/camel-route-diagnostics").get(name, {})


def healthy(name, writes, scripts, processed=0):
    data = diagnostics(name)
    assert data.get("failed", 0) == 0, data
    assert data.get("rejected", 0) == 0, data
    return (data.get("writes", 0) >= writes and data.get("scriptCalls", 0) >= scripts
            and data.get("processed", 0) >= processed)


def import_project(name: str, source: str | Path | None = None,
                   parent: str = "", inheritable: bool = False) -> None:
    """Import fixture directories or generated scripts through Ignition's native API."""
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as project:
        match source:
            case Path():
                for path in sorted(source.rglob("*")):
                    if path.is_file():
                        project.write(path, path.relative_to(source).as_posix())
            case str() | None:
                project.writestr("project.json", json.dumps({
                    "title": name, "enabled": True, "inheritable": inheritable, "parent": parent,
                }))
                if source is not None:
                    project.writestr("ignition/script-python/routes/resource.json", json.dumps({
                        "scope": "A", "version": 1, "restricted": False, "overridable": True,
                        "files": ["code.py"], "attributes": {"hintScope": 1},
                    }))
                    project.writestr("ignition/script-python/routes/code.py", source)
            case unreachable:
                assert_never(unreachable)
    request = urllib.request.Request(
        BASE + "/data/api/v1/projects/import/" + name + "?overwrite=true", method="POST",
        data=archive.getvalue(), headers={"Content-Type": "application/zip", "X-CSRF-Token": CSRF},
    )
    with HTTP.open(request, timeout=30) as response:
        result = json.load(response)
        assert result.get("success"), result


def reload_source(version):
    return '''def orders(builder):
    (builder.from_("timer:reload?repeatCount=1&delay=500")
        .routeId("project-reload-proof")
        .setBody().constant(%d)
        .to("ignition-script:camel_fixture.recordReload?project=camel_acceptance"))
''' % version


def verify_project_reload():
    parent = "camel_reload_parent"
    child = "camel_reload_child"
    route = "camel-acceptance-project-reload"
    observer = "camel-acceptance-reload-observer"
    delete(route)
    delete(observer)
    # Start the test's own project pair fresh, independent of previous failed runs.
    # Never touch the developer's projects.
    for name in (child, parent):
        try:
            api("DELETE", "/data/api/v1/projects/" + name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    import_project(parent, reload_source(1), inheritable=True)
    import_project(child, parent=parent)
    create(observer, """
- route:
    from:
      uri: ignition-tag:[default]CamelReloadProof
      steps:
        - filter:
            simple: '${header.ignitionTagGood} == true && ${body} == 2'
            steps:
              - to: ignition-script:camel_fixture.assertReload2?project=camel_acceptance
""")
    create(route, "routes.orders", language="JYTHON", project=child)
    wait("inherited project route", lambda: healthy(route, 0, 1))
    before = diagnostics(route)["generation"]
    yaml_generation = diagnostics("camel-acceptance-transfer")["generation"]
    other_project_generation = diagnostics("camel-acceptance-jython")["generation"]
    assert diagnostics(observer)["scriptCalls"] == 0, "updated value arrived before the project update"

    import_project(parent, reload_source(2), inheritable=True)
    wait("inherited script reload", lambda: diagnostics(route).get("generation", 0) > before)
    wait("new project definition actually delivered 2", lambda: healthy(observer, 0, 1))
    assert diagnostics("camel-acceptance-transfer")["generation"] == yaml_generation
    assert diagnostics("camel-acceptance-jython")["generation"] == other_project_generation

    import_project(parent, "def removed(builder):\n    pass\n", inheritable=True)
    wait("missing function stops its route", lambda: api("GET", MODULE + "/camel-routes").get(route) == "ERROR")
    assert route not in api("GET", MODULE + "/camel-route-diagnostics")
    import_project(parent, reload_source(2), inheritable=True)
    wait("restored function recovers without editing the pointer", lambda: healthy(route, 0, 1))

    # This API replaces project settings rather than patching them; retain inheritance.
    settings = api("GET", "/data/api/v1/projects/find/" + child)
    settings["enabled"] = False
    api("PUT", "/data/api/v1/projects/" + child, settings)
    wait("disabled project stops its route", lambda: api("GET", MODULE + "/camel-routes").get(route) == "ERROR")
    settings["enabled"] = True
    api("PUT", "/data/api/v1/projects/" + child, settings)
    wait("enabled project recovers its route", lambda: healthy(route, 0, 1))
    print("  inherited project updates rebuilt only affected routes; missing functions and disabled projects recovered")


def verify_camel_api():
    target = "camel-acceptance-api-target"
    caller = "camel-acceptance-api-caller"
    delete(caller)
    delete(target)
    create(target, """
- route:
    from:
      uri: direct:echo
      steps:
        - to: log:camel-api-echo?showBody=false
- route:
    from:
      uri: direct:header
      steps:
        - setBody:
            simple: '${header.proof}'
- route:
    from:
      uri: direct:send
      steps:
        - to: ignition-script:camel_fixture.jythonProof?project=camel_acceptance
- route:
    from:
      uri: direct:fail
      steps:
        - to: ignition-script:camel_fixture.fail?project=camel_acceptance
""")
    wait("scripting API target", lambda: api("GET", MODULE + "/camel-routes").get(target) == "RUNNING")
    before = diagnostics(target)["generation"]
    create(caller, """
- route:
    from:
      uri: timer:api-proof?repeatCount=1&delay=500
      steps:
        - to: ignition-script:camel_fixture.verifyCamelApi?project=camel_acceptance
""")
    wait("system.camel API from a real project script", lambda: healthy(caller, 0, 1))
    wait("system.camel queued targeted restart", lambda: diagnostics(target).get("generation", 0) > before)
    print("  system.camel list/status/send/request/restart, headers, null/scalar/collection payloads and failures verified")


def verify_yaml_file():
    root = "/usr/local/bin/ignition/data/config/resources/core/com.noahburnette.ignition.apachecamel/camel-route/"
    def read_file(name, resource="camel-acceptance-transfer"):
        return subprocess.check_output(["docker", "compose", "-f", str(Path(__file__).resolve().parents[1] / "docker/camel-acceptance.yml"),
                                        "exec", "-T", "gateway", "cat", root + resource + "/" + name], text=True)
    assert "yaml" not in json.loads(read_file("config.json")), "YAML was persisted inline"
    assert "config.camel.yaml" in json.loads(read_file("resource.json"))["files"], "YAML was not declared in the resource manifest"
    assert "ignition-tag:" in read_file("config.camel.yaml")
    name = "camel-acceptance-file-roundtrip"
    delete(name)
    source = "- route:\n    from:\n      uri: direct:file\n      steps:\n        - to: log:file\n"
    create(name, source)
    resource = api("GET", "/data/api/v1/resources/find/" + TYPE + "/" + name)
    changed = source.replace("log:file", "log:edited")
    api("PUT", "/data/api/v1/resources/" + TYPE, [{
        "name": name, "collection": "core", "signature": resource["signature"],
        "config": {"language": "YAML", "yaml": changed},
        "backupConfig": {"language": "YAML", "yaml": source},
    }])
    saved = api("GET", "/data/api/v1/resources/find/" + TYPE + "/" + name)
    assert saved["config"]["yaml"] == changed
    assert saved["backupConfig"]["yaml"] == source
    assert read_file("config.camel.yaml", name) == changed
    assert read_file("backup-config.camel.yaml", name) == source
    assert "yaml" not in json.loads(read_file("config.json", name))
    assert "yaml" not in json.loads(read_file("backupConfig.json", name))
    files = json.loads(read_file("resource.json", name))["files"]
    assert {"config.camel.yaml", "backup-config.camel.yaml"}.issubset(files)
    delete(name)
    print("  YAML create/modify/backup round-trip uses declared resource files, not inline JSON")


def verify_http():
    name = "camel-acceptance-http"
    delete(name)
    create(name, """
- route:
    from:
      uri: ignition-gateway-http:/number?httpMethodRestrict=POST
      steps:
        - to: ignition-script:camel_fixture.httpNumber?project=camel_acceptance
        - to: ignition-tag:[default]CamelHttpNumber
        - to: ignition-script:camel_fixture.httpResponse?project=camel_acceptance
- route:
    from:
      uri: ignition-gateway-http:/fail?httpMethodRestrict=POST
      steps:
        - to: ignition-script:camel_fixture.fail?project=camel_acceptance
- route:
    from:
      uri: ignition-gateway-http:/echo?httpMethodRestrict=POST
      steps:
        - to: log:camel-http-echo?showBody=false
- route:
    from:
      uri: ignition-gateway-http:/decline?httpMethodRestrict=POST
      steps:
        - setHeader:
            name: CamelHttpResponseCode
            constant: 422
        - setBody:
            constant: route-declined
""")
    wait("HTTP consumer startup", lambda: api("GET", MODULE + "/camel-routes").get(name) == "RUNNING")
    path = "/system/integrations/" + name

    def post(suffix, data=b'null', status=200, csrf=True, content_type="application/json", opener=HTTP):
        headers = {"Content-Type": content_type, "CamelHttpPath": "/spoofed", "CamelHttpResponseCode": "499", "X-Client-Secret": "not-for-the-route"}
        if csrf:
            headers["X-CSRF-Token"] = CSRF
        request = urllib.request.Request(BASE + path + suffix, data=data, method="POST", headers=headers)
        try:
            with opener.open(request, timeout=20) as response:
                assert response.status == status, response.status
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            assert error.code == status, (error.code, error.read().decode())
            raw = error.read()
            try:
                return json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return raw.decode(errors="replace")  # Gateway auth/CSRF errors may be HTML.

    result = post("/number", json.dumps({"number": 9007199254740993}).encode())
    assert result["number"] == 9007199254740994 and result["writeVerified"], result
    for payload in [None, 9007199254740993, [1, "two"], {"nested": [True, None]}]:
        assert post("/echo", json.dumps(payload).encode()) == payload
    assert post("/decline", status=422) == "route-declined"
    assert post("/missing", status=404)["error"]
    assert post("/echo", data=b'{', status=400)["error"]
    assert post("/echo", data=b'"' + b'x' * 65536 + b'"', status=413)["error"]
    assert post("/echo", data=b'plain text', content_type="text/plain", status=415)["error"]
    failed = post("/fail", status=500)
    assert "Traceback" not in json.dumps(failed) and "Intentional" not in json.dumps(failed), failed
    received_before_auth_checks = diagnostics(name)["httpReceived"]
    post("/number", status=403, csrf=False)
    post("/number", status=401, opener=urllib.request.build_opener())
    assert diagnostics(name)["httpReceived"] == received_before_auth_checks, "unauthorized request reached Camel"
    try:
        with HTTP.open(BASE + path + "/echo", timeout=15):
            raise AssertionError("GET unexpectedly reached a POST-only endpoint")
    except urllib.error.HTTPError as error:
        assert error.code in (404, 405), error.code

    definition = find(name)
    api("PUT", "/data/api/v1/resources/" + TYPE, [{"name": name, "signature": definition["signature"], "enabled": False}])
    wait("disabled HTTP consumer", lambda: api("GET", MODULE + "/camel-routes").get(name) == "DISABLED")
    post("/echo", status=404)
    definition = find(name)
    api("PUT", "/data/api/v1/resources/" + TYPE, [{"name": name, "signature": definition["signature"], "enabled": True}])
    wait("re-enabled HTTP consumer", lambda: api("GET", MODULE + "/camel-routes").get(name) == "RUNNING")
    assert post("/echo", data=b'"recovered"') == "recovered"
    stored_yaml = find(name)["config"]["yaml"]
    delete(name)
    post("/echo", status=404)
    create(name, stored_yaml)
    wait("recreated HTTP consumer", lambda: api("GET", MODULE + "/camel-routes").get(name) == "RUNNING")
    assert post("/echo", data=b'"recreated"') == "recreated"
    print("  direct public HTTP -> script -> real tag -> JSON reply; CSRF, header filtering, errors, limits and disable/enable verified")


def verify_camel_details():
    catalog = api("GET", MODULE + "/camel-components")
    assert catalog["camelVersion"] == "4.22.2", catalog
    components = {scheme for entry in catalog["entries"] for scheme in entry["components"]}
    assert {"seda", "bean", "file", "sftp", "quartz", "scheduler", "ignition-gateway-http"}.issubset(components)
    assert "platform-http" not in components, "Removed alias must not be advertised"
    name = "camel-acceptance-transfer"
    details = api("GET", MODULE + "/camel-route-details?name=" + name)
    by_id = {route["id"]: route for route in details["routes"]}
    assert {"transform", "observe", "produce"}.issubset(by_id), details
    assert by_id["transform"]["completed"] >= 1 and by_id["produce"]["completed"] >= 1
    assert details["metrics"]["inflight"] == 0
    assert by_id["transform"]["meanProcessingMillis"] > 0
    assert by_id["transform"]["steps"] and by_id["transform"]["sourceComponent"] == "ignition-tag"
    assert "yaml" not in details and "payload" not in details
    before = details["diagnostics"]["generation"]
    definition = find(name)
    api("PUT", "/data/api/v1/resources/" + TYPE, [{"name": name, "signature": definition["signature"], "config": definition["config"]}])
    assert api("GET", MODULE + "/camel-route-details?name=" + name)["diagnostics"]["generation"] == before

    name = "camel-acceptance-http"
    try:
        api("POST", "/system/integrations/" + name + "/fail", None)
        raise AssertionError("Expected HTTP failure")
    except urllib.error.HTTPError as error:
        assert error.code == 500
    details = api("GET", MODULE + "/camel-route-details?name=" + name)
    assert details["metrics"]["failed"] >= 1, details
    assert any(route["lastErrorType"] for route in details["routes"]), details
    assert "Intentional Camel scripting API delivery failure" not in json.dumps(details)
    before = details["diagnostics"]["generation"]
    api("POST", MODULE + "/camel-route-restart?name=" + name)
    wait("details restart generation", lambda: diagnostics(name).get("generation", 0) > before)
    details = api("GET", MODULE + "/camel-route-details?name=" + name)
    assert details["metrics"]["completed"] == 0 and details["metrics"]["failed"] == 0, details

    disabled = "camel-acceptance-details-disabled"
    delete(disabled)
    create(disabled, "- route:\n    from:\n      uri: direct:disabled\n      steps:\n        - to: log:disabled\n", enabled=False)
    details = api("GET", MODULE + "/camel-route-details?name=" + disabled)
    assert details["state"] == "DISABLED" and details["routes"] == [], details
    delete(disabled)
    print("  per-route counters/latency/model details, sanitized failures, unchanged preservation, queued restart reset and bundled catalogue verified")


SETUP = """
- route:
    from:
      uri: timer:setup?repeatCount=1&delay=1000
      steps:
        - to: ignition-script:camel_fixture.setup?project=camel_acceptance
"""

TRANSFER = """
- route:
    id: transform
    from:
      uri: ignition-tag:[default]CamelInput
      steps:
        - filter:
            simple: '${header.ignitionTagGood} == true && ${body} != 0'
            steps:
              - to: ignition-script:camel_fixture.transform?project=camel_acceptance
              - to: ignition-tag:[default]CamelOutput
- route:
    id: observe
    from:
      uri: ignition-tag:[default]CamelOutput
      steps:
        - filter:
            simple: '${header.ignitionTagGood} == true && ${body} != 0'
            steps:
              - to: ignition-script:camel_fixture.observe?project=camel_acceptance
- route:
    id: produce
    from:
      uri: timer:produce?repeatCount=1&delay=2000
      steps:
        - setBody:
            simple:
              expression: '9007199254740993'
              resultType: long
        - to: ignition-tag:[default]CamelInput
"""

JYTHON = "camel_routes.orders"

MQTT5 = """
- route:
    id: mqtt5-consume
    from:
      uri: paho-mqtt5:apache-camel/camel/mqtt5-proof?brokerUrl=tcp://mqtt:1883&clientId=camel-mqtt5-consumer&qos=1&cleanStart=true&sessionExpiryInterval=0&persistence=MEMORY&connectionTimeout=5
      steps:
        - convertBodyTo:
            type: java.lang.String
            charset: UTF-8
        - unmarshal:
            json:
              library: Jackson
        - to: ignition-script:camel_fixture.mqtt5Transform?project=camel_acceptance
        - to: ignition-tag:[default]CamelMqtt5Number
        - to: ignition-script:camel_fixture.mqtt5Observe?project=camel_acceptance
- route:
    id: mqtt5-publish
    from:
      uri: ignition-gateway-http:/publish?httpMethodRestrict=POST
      steps:
        - marshal:
            json:
              library: Jackson
        - to: paho-mqtt5:apache-camel/camel/mqtt5-proof?brokerUrl=tcp://mqtt:1883&clientId=camel-mqtt5-producer&qos=1&retained=true&cleanStart=true&sessionExpiryInterval=0&persistence=MEMORY&connectionTimeout=5
        - setBody:
            constant: published
- route:
    id: mqtt5-snapshot
    from:
      uri: ignition-gateway-http:/snapshot?httpMethodRestrict=POST
      steps:
        - to: ignition-script:camel_fixture.mqtt5Snapshot?project=camel_acceptance
"""


def verify_mqtt5(restart=False):
    name = "camel-acceptance-mqtt5"
    topic = "apache-camel/camel/mqtt5-proof"
    compose = ["docker", "compose", "-f", str(Path(__file__).resolve().parents[1] / "docker/camel-acceptance.yml")]
    endpoint = "/system/integrations/" + name
    if not restart:
        delete(name)
        subprocess.run(compose + ["exec", "-T", "mqtt", "mosquitto_pub", "-V", "mqttv5", "-h", "localhost", "-t", topic, "-r", "-n"], check=True, timeout=15)
        create(name, MQTT5)
    wait("native MQTT 5 route startup", lambda: api("GET", MODULE + "/camel-routes").get(name) == "RUNNING")
    assert api("POST", endpoint + "/publish", {"number": 9007199254740993}) == "published"
    wait("MQTT 5 broker -> script -> tag", lambda: healthy(name, 1, 2))
    wait("actual MQTT 5 output tag", lambda: api("POST", endpoint + "/snapshot", {}).get("CamelMqtt5Number") == 9007199254740994)
    received = subprocess.check_output(compose + ["exec", "-T", "mqtt", "mosquitto_sub", "-V", "mqttv5", "-h", "localhost", "-t", topic, "-q", "1", "-C", "1", "-W", "10"], text=True, timeout=15)
    assert json.loads(received)["number"] == 9007199254740993, received
    if not restart:
        before = diagnostics(name)["writes"]
        subprocess.run(compose + ["exec", "-T", "mqtt", "mosquitto_pub", "-V", "mqttv5", "-h", "localhost", "-t", topic, "-q", "1", "-m", '{"number":7001}'], check=True, timeout=15)
        wait("external MQTT publisher -> native Camel consumer", lambda: healthy(name, before + 1, 4))
        assert api("POST", endpoint + "/snapshot", {})["CamelMqtt5Number"] == 7002
        for enabled in (False, True):
            resource = find(name)
            api("PUT", "/data/api/v1/resources/" + TYPE, [{"name": name, "signature": resource["signature"], "enabled": enabled}])
            state = "RUNNING" if enabled else "DISABLED"
            wait("MQTT 5 " + state, lambda: api("GET", MODULE + "/camel-routes").get(name) == state)
        wait("MQTT 5 resubscription receives retained proof", lambda: healthy(name, 1, 2))
        assert api("POST", endpoint + "/snapshot", {})["CamelMqtt5Number"] == 9007199254740994
    details = api("GET", MODULE + "/camel-route-details?name=" + name)
    consumer = next(route for route in details["routes"] if route["id"] == "mqtt5-consume")
    assert consumer["sourceComponent"] == "paho-mqtt5" and consumer["completed"] >= 1 and consumer["failed"] == 0, consumer
    print("  native MQTT 5 publish/subscribe, independent MQTT 5 broker clients, JSON/int64/script/tag proof and " + ("Gateway restart recovery" if restart else "disable/enable resubscription") + " verified")


def main():
    wait("isolated Gateway login", bootstrap)
    wait("Gateway module API", lambda: api("GET", MODULE + "/camel-routes") is not None)
    navigation = json.dumps(api("GET", "/data/app/navigation"))
    assert "Apache Camel" in navigation and "/services/apache-camel/integrations" in navigation, navigation
    entities = api("GET", "/data/api/v1/entity/section/Services")
    camel = next(entity for entity in entities if entity["name"] == "Apache Camel")
    assert "section-overview" in camel["navigation"]["tags"], camel
    assert isinstance(camel.get("diagnostics", {}).get("metrics"), dict), camel
    assert camel["diagnostics"]["healthchecks"]["status"]["result"]["healthy"], camel
    if "--verify-restart" in sys.argv:
        wait("persisted Camel route startup", lambda: api("GET", MODULE + "/camel-routes").get(
            "camel-acceptance-transfer") == "RUNNING")
        wait("delivery after Gateway restart", lambda: healthy("camel-acceptance-transfer", 2, 2, 2))
        wait("Jython DSL after Gateway restart", lambda: healthy("camel-acceptance-jython", 0, 1))
        wait("HTTP consumer after Gateway restart", lambda: api("GET", MODULE + "/camel-routes").get("camel-acceptance-http") == "RUNNING")
        result = api("POST", "/system/integrations/camel-acceptance-http/number", {"number": 123})
        assert result["number"] == 124 and result["writeVerified"], result
        verify_mqtt5(restart=True)
        print("  persisted YAML/Jython delivery and incoming HTTP -> tag -> JSON verified after Gateway restart")
        return

    import_project("camel_acceptance", Path(__file__).resolve().parents[1] / "docker/camel-project")

    # These names belong exclusively to this isolated acceptance stack.
    delete("camel-acceptance-transfer")
    delete("camel-acceptance-setup")
    create("camel-acceptance-setup", SETUP)
    wait("tag fixture configuration", lambda: healthy("camel-acceptance-setup", 0, 1))
    create("camel-acceptance-transfer", TRANSFER)
    wait("tag -> project script -> tag -> observer", lambda: healthy("camel-acceptance-transfer", 2, 2, 2))
    print("  real tag subscriptions, project scripts, writes, quality and Int64 precision verified")

    delete("camel-acceptance-jython")
    create("camel-acceptance-jython", JYTHON, language="JYTHON")
    wait("native Jython DSL and retained Python Processor globals", lambda: healthy("camel-acceptance-jython", 0, 1))
    assert find("camel-acceptance-jython")["config"]["language"] == "JYTHON"
    try:
        create("camel-acceptance-invalid-jython", "camel_routes.orders()", language="JYTHON")
        raise AssertionError("Invalid project function reference was persisted")
    except urllib.error.HTTPError as error:
        assert error.code == 422, error.code
        assert "functionPath" in json.dumps(json.loads(error.read()).get("fieldMessages", {}))
    print("  Apache Camel navigation, project-library Jython DSL, callbacks and reference validation verified")

    delete("camel-acceptance-disabled")
    create("camel-acceptance-disabled", TRANSFER, enabled=False)
    wait("disabled resource", lambda: api("GET", MODULE + "/camel-routes").get(
        "camel-acceptance-disabled") == "DISABLED")
    assert "camel-acceptance-disabled" not in api("GET", MODULE + "/camel-route-diagnostics")
    delete("camel-acceptance-disabled")

    try:
        create("camel-acceptance-invalid-yaml", "- route: [invalid")
        raise AssertionError("Invalid YAML was persisted")
    except urllib.error.HTTPError as error:
        assert error.code == 422, error.code
        validation = json.loads(error.read())
        assert "yaml" in json.dumps(validation.get("fieldMessages", {})), validation
    assert find("camel-acceptance-invalid-yaml") is None

    delete("camel-acceptance-missing-component")
    create("camel-acceptance-missing-component", """
- route:
    from:
      uri: not-bundled:missing
      steps:
        - to: log:unused
""")
    wait("isolated startup failure", lambda: api("GET", MODULE + "/camel-routes").get(
        "camel-acceptance-missing-component") == "ERROR")
    assert api("GET", MODULE + "/camel-routes")["camel-acceptance-transfer"] == "RUNNING"
    delete("camel-acceptance-missing-component")
    print("  disabled definitions, YAML validation and startup failure isolation verified")

    # Delete and recreate the route to prove subscriptions are released rather than leaked.
    delete("camel-acceptance-transfer")
    assert "camel-acceptance-transfer" not in api("GET", MODULE + "/camel-route-diagnostics")
    delete("camel-acceptance-setup")
    create("camel-acceptance-setup", SETUP)
    wait("reset tag fixtures", lambda: healthy("camel-acceptance-setup", 0, 1))
    create("camel-acceptance-transfer", TRANSFER)
    wait("delivery after replacement", lambda: healthy("camel-acceptance-transfer", 2, 2, 2))
    print("  resource deletion, subscription cleanup and replacement verified")
    verify_project_reload()
    verify_camel_api()
    verify_yaml_file()
    verify_http()
    verify_camel_details()
    verify_mqtt5()


if __name__ == "__main__":
    main()
