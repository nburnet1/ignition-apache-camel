"""Native send/request and a Perspective component-handler fixture; isolated Gateway only."""
import io
import json
import runpy
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
PROJECT = "camel_message_proof"
ROUTE = "camel-message-proof"
MODULE = m["MODULE"]
ENDPOINT = "/system/integrations/" + ROUTE


def provision_project():
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("project.json", json.dumps({"title": PROJECT, "enabled": True, "inheritable": False}))
        def resource(path, files, attributes=None, scope="G"):
            output.writestr(path + "/resource.json", json.dumps({"scope": scope, "version": 1,
                "restricted": False, "overridable": True, "files": list(files), "attributes": attributes or {}}))
            for name, content in files.items():
                output.writestr(path + "/" + name, content)
        resource("ignition/script-python/routes", {"code.py": '''def backend(payload, headers):
    return {"via": "camel", "payload": payload}

def probe(payload, headers):
    value = system.tag.readBlocking(["[default]CamelMessageSent"])[0]
    return value.value if value.quality.isGood() else None

def sessions(payload, headers):
    return system.perspective.getSessionInfo()
'''}, {"hintScope": 1}, "A")
        handlers = {
            "echo": "return payload",
            "bridge": 'return system.camel.request("camel-message-proof", "direct:backend", payload)',
            "notify": '''system.tag.configure("[default]", [{"name":"CamelMessageSent", "tagType":"AtomicTag", "valueSource":"memory", "dataType":"String", "value":payload["text"]}], "m")''',
            "fail": 'raise ValueError("expected handler failure")',
            "slow": "import time\ntime.sleep(2)\nreturn payload",
        }
        for name, source in handlers.items():
            code = "def handleMessage(payload):\n" + "\n".join("\t" + line for line in source.splitlines()) + "\n"
            resource("ignition/message/" + name, {"handleMessage.py": code}, {"enabled": True, "threadType": "Dedicated"})
        resource("com.inductiveautomation.perspective/page-config", {"config.json": json.dumps({"pages": {"/": {"viewPath": "Messages", "title": "Camel messaging", "docks": {}}}})})
        view = {"props": {"defaultSize": {"width": 600, "height": 300}}, "params": {}, "custom": {},
            "root": {"type": "ia.container.flex", "meta": {"name": "root"}, "props": {"direction": "column"},
                "children": [{"type": "ia.display.label", "meta": {"name": "message"}, "props": {"text": "Waiting for Camel message"},
                    "scripts": {"messageHandlers": [{"messageType": "camelUpdated", "script": "\tself.props.text = payload['text']",
                        "sessionScope": True, "pageScope": True, "viewScope": False}]}}]}}
        resource("com.inductiveautomation.perspective/views/Messages", {"view.json": json.dumps(view)})
    request = urllib.request.Request(m["BASE"] + "/data/api/v1/projects/import/" + PROJECT + "?overwrite=true",
        method="POST", data=archive.getvalue(), headers={"Content-Type": "application/zip", "X-CSRF-Token": api("GET", "/data/app/session")["csrfToken"]})
    with m["HTTP"].open(request, timeout=30) as response:
        assert json.load(response).get("success")


def main():
    wait("Gateway login", m["bootstrap"], seconds=180)
    if "--perspective" in sys.argv:
        session, page = sys.argv[-2:]
        import uuid
        uuid.UUID(session)
        assert page.replace("-", "").isalnum(), "Unexpected page ID"
        name = "camel-perspective-message-proof"
        m["delete"](name)
        m["create"](name, f'''- route:
    from:
      uri: ignition-gateway-http:/notify?httpMethodRestrict=POST
      steps:
        - to: ignition-perspective-message:camelUpdated?sessionId={session}&pageId={page}
''')
        wait("Perspective message route", lambda: api("GET", MODULE + "/camel-routes").get(name) == "RUNNING")
        assert api("POST", "/system/integrations/" + name + "/notify", {"text": "Delivered from Camel"}) == {"text": "Delivered from Camel"}
        print("Perspective page notification dispatched; verify the component changed to 'Delivered from Camel'")
        return
    provision_project()
    m["delete"](ROUTE)
    definitions = []
    for path, target in [("request", "ignition-message:echo?project=" + PROJECT),
                         ("bridge", "ignition-message:bridge?project=" + PROJECT),
                         ("send", "ignition-message:notify?project=" + PROJECT + "&mode=send"),
                         ("fail", "ignition-message:fail?project=" + PROJECT),
                         ("slow", "ignition-message:slow?project=" + PROJECT + "&timeoutSec=1"),
                         ("missing", "ignition-message:missing?project=" + PROJECT),
                         ("probe", "ignition-script:routes.probe?project=" + PROJECT),
                         ("sessions", "ignition-script:routes.sessions?project=" + PROJECT)]:
        definitions.append(f'''- route:
    from:
      uri: ignition-gateway-http:/{path}?httpMethodRestrict=POST
      steps:
        - to: {target}
''')
    definitions.append(f'''- route:
    from:
      uri: direct:backend
      steps:
        - to: ignition-script:routes.backend?project={PROJECT}
''')
    m["create"](ROUTE, "\n".join(definitions))
    wait("native messaging routes", lambda: api("GET", MODULE + "/camel-routes").get(ROUTE) == "RUNNING")
    payload = {"text": "native-send-proof", "number": 9007199254740993}
    wait("project message handler startup", lambda: api("POST", ENDPOINT + "/request", payload) == payload)
    assert api("POST", ENDPOINT + "/bridge", payload) == {"via": "camel", "payload": payload}
    assert api("POST", ENDPOINT + "/send", payload) == payload
    wait("native send handler executes", lambda: api("POST", ENDPOINT + "/probe", {}) == payload["text"])
    for path in ["fail", "missing", "slow"]:
        started = time.monotonic()
        try:
            api("POST", ENDPOINT + "/" + path, payload)
            raise AssertionError("Expected message failure: " + path)
        except urllib.error.HTTPError as error:
            assert error.code >= 400, error.code
            assert b"expected handler failure" not in error.read()
        if path == "slow":
            assert time.monotonic() - started < 1.9, "Native request timeout was not applied"
    try:
        api("POST", ENDPOINT + "/request", "not-a-mapping")
        raise AssertionError("Scalar payload accepted")
    except urllib.error.HTTPError as error:
        assert error.code >= 400
    print("Native Gateway send/request, handler->Camel reply, Int64 precision, failures, missing handler, timeout and object payload verified")
    print("Perspective fixture: " + m["BASE"] + "/data/perspective/client/" + PROJECT)


if __name__ == "__main__":
    main()
