"""Trusted extension upload/restart/discovery proof on the isolated 9288 Gateway."""
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
BASE, MODULE = m["BASE"], m["MODULE"]
ROOT = Path(__file__).resolve().parents[1]
COMPONENT = "com.noahburnette.ignition.apachecamel/camel-component"
ROUTE = "com.noahburnette.ignition.apachecamel/camel-route"
NAME = "camel-acceptance-extension"
SCHEME = "acceptance-addon"


def raw_upload(name, data, csrf=True, authenticated=True, confirm=True):
    token = api("GET", "/data/app/session")["csrfToken"]
    request = urllib.request.Request(BASE + MODULE + "/camel-extension-upload?" + urllib.parse.urlencode({"name": name, "confirm": str(confirm).lower()}),
        method="POST", data=data, headers={"Content-Type": "application/java-archive", **({"X-CSRF-Token": token} if csrf else {})})
    opener = m["HTTP"] if authenticated else urllib.request.build_opener()
    with opener.open(request, timeout=30) as response:
        return json.load(response)


def expect_denied(action):
    try:
        action()
    except urllib.error.HTTPError as error:
        assert error.code in (400, 401, 403), error.code
    else:
        raise AssertionError("Unsafe upload was accepted")


def jar(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), content)
    return output.getvalue()


def stage():
    harmless = jar({"acceptance.txt": b"probe"})
    expect_denied(lambda: raw_upload("probe.jar", harmless, csrf=False))
    expect_denied(lambda: raw_upload("probe.jar", harmless, authenticated=False))
    expect_denied(lambda: raw_upload("probe.jar", harmless, confirm=False))
    expect_denied(lambda: raw_upload("../probe.jar", harmless))
    expect_denied(lambda: raw_upload("corrupt.jar", b"not a jar"))
    inventory = api("GET", MODULE + "/camel-extensions")["files"]
    existing = {file["name"] for file in inventory if file["state"] != "REMOVAL_PENDING"}
    with tempfile.TemporaryDirectory(prefix="camel-extension-") as temporary:
        directory = Path(temporary)
        (directory / "Helper.java").write_text('package acceptance; public class Helper { public static String decorate(String value) { return "extension:" + value; } }')
        (directory / "AddonComponent.java").write_text('''package acceptance;
import org.apache.camel.*;
import org.apache.camel.support.*;
import java.util.Map;
public class AddonComponent extends DefaultComponent {
    private String greeting;
    public void setGreeting(String value) { greeting = value; }
    protected Endpoint createEndpoint(String uri, String remaining, Map<String,Object> parameters) {
        return new DefaultEndpoint(uri, this) {
            public Producer createProducer() { return new DefaultProducer(this) {
                public void process(Exchange exchange) { exchange.getMessage().setBody(Helper.decorate(greeting)); }
            }; }
            public Consumer createConsumer(Processor processor) { throw new UnsupportedOperationException(); }
            public boolean isSingleton() { return true; }
        };
    }
}''')
        java = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
        bundled = next((ROOT / "gateway/build/libs").glob("*-all.jar"))
        subprocess.run([str(java / "bin/javac"), "-cp", str(bundled), "-d", temporary, str(directory / "Helper.java"), str(directory / "AddonComponent.java")], check=True)
        dependency = jar({"acceptance/Helper.class": (directory / "acceptance/Helper.class").read_bytes()})
        entries = {"acceptance/" + path.name: path.read_bytes() for path in (directory / "acceptance").glob("AddonComponent*.class")}
        entries["META-INF/services/org/apache/camel/component/" + SCHEME] = b"class=acceptance.AddonComponent\n"
        entries["META-INF/org/apache/camel/component/" + SCHEME + "/" + SCHEME + ".json"] = json.dumps({
            "component": {"scheme": SCHEME, "title": "Acceptance extension", "javaType": "acceptance.AddonComponent", "syntax": SCHEME + ":operation"},
            "componentProperties": {"greeting": {"type": "string", "required": True, "description": "Greeting to return"}}, "properties": {},
        }).encode()
        for name, data in (("acceptance-helper.jar", dependency), ("acceptance-addon.jar", jar(entries))):
            if name not in existing:
                raw_upload(name, data)
                expect_denied(lambda: raw_upload(name, data))
    print("Extension staging, authentication, CSRF, confirmation and invalid upload checks passed")


def verify():
    files = api("GET", MODULE + "/camel-extensions")["files"]
    assert all(any(file["name"] == name and file["state"] == "ACTIVE" for file in files) for name in ("acceptance-helper.jar", "acceptance-addon.jar"))
    schemas = api("GET", MODULE + "/camel-component-metadata")["components"]
    assert any(item["component"]["scheme"] == SCHEME for item in schemas)
    config = {"type": "GENERIC", "generic": {"scheme": SCHEME, "options": {"greeting": "hello"}}}
    for kind, name in ((ROUTE, NAME), (COMPONENT, "proof-addon")):
        try:
            existing = api("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        else:
            api("POST", "/data/api/v1/resources/delete/" + kind + "?confirm=true", [{"name": name, "signature": existing["signature"]}])
    invalid = {"type": "GENERIC", "generic": {"scheme": SCHEME, "options": {"unknown": "bad"}}}
    try:
        api("POST", "/data/api/v1/resources/" + COMPONENT, [{"name": "proof-addon", "config": invalid}])
    except urllib.error.HTTPError as error:
        assert error.code in (400, 422), error.code
    else:
        raise AssertionError("Unknown and missing required options were accepted")
    api("POST", "/data/api/v1/resources/" + COMPONENT, [{"name": "proof-addon", "config": config}])
    yaml = '''- route:
    from:
      uri: ignition-gateway-http:/echo?httpMethodRestrict=POST
      steps:
        - to: proof-addon:run
'''
    api("POST", "/data/api/v1/resources/" + ROUTE, [{"name": NAME, "config": {"yaml": yaml, "components": ["proof-addon"], "httpSecurityLevels": ["Authenticated/Roles/Administrator"]}}])
    wait("uploaded component route", lambda: api("GET", MODULE + "/camel-routes").get(NAME) == "RUNNING")
    assert api("POST", "/system/integrations/" + NAME + "/echo", {}) == "extension:hello"
    print("Uploaded component, separate dependency, metadata validation and live Gateway delivery passed")


if __name__ == "__main__":
    wait("isolated Gateway login", m["bootstrap"])
    verify() if "--verify-restart" in sys.argv else stage()
