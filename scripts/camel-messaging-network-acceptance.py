"""Opt-in two-Gateway proof using native TLS/approval; no developer Gateway state."""
import io
import json
import runpy
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

HELPER = str(Path(__file__).with_name("camel-acceptance.py"))
source = runpy.run_path(HELPER)
peer = runpy.run_path(HELPER)
peer["api"].__globals__["BASE"] = "http://localhost:9289"
PROJECT = "camel_message_peer"
ROUTE = "camel-message-network-proof"


def approve_certificate(client, expected, is_client):
    path = "/data/api/v1/gateway-network/certificates/"
    def expected_certificate():
        return next((cert for cert in client["api"]("GET", path + "list/" + is_client)
            if cert["digest"] == expected), None)
    cert = client["wait"]("specific isolated Gateway certificate", expected_certificate, seconds=90)
    if cert["status"] != "Approved":
        client["api"]("PUT", path + "approve/" + is_client, [expected])


def provision_peer():
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("project.json", json.dumps({"title": PROJECT, "enabled": True, "inheritable": False}))
        for name, body in {
            "echo": 'return {"gateway":"camel-message-peer", "payload":payload}',
            "notify": 'system.tag.configure("[default]", [{"name":"CamelRemoteMessage", "tagType":"AtomicTag", "valueSource":"memory", "dataType":"String", "value":payload["text"]}], "m")',
            "probe": 'return system.tag.readBlocking(["[default]CamelRemoteMessage"])[0].value',
        }.items():
            root = "ignition/message/" + name
            output.writestr(root + "/resource.json", json.dumps({"scope": "G", "version": 1, "restricted": False,
                "overridable": True, "files": ["handleMessage.py"], "attributes": {"enabled": True, "threadType": "Dedicated"}}))
            output.writestr(root + "/handleMessage.py", "def handleMessage(payload):\n\t" + body + "\n")
    request = urllib.request.Request("http://localhost:9289/data/api/v1/projects/import/" + PROJECT + "?overwrite=true",
        method="POST", data=archive.getvalue(), headers={"Content-Type": "application/zip",
            "X-CSRF-Token": peer["api"]("GET", "/data/app/session")["csrfToken"]})
    with peer["HTTP"].open(request, timeout=30) as response:
        assert json.load(response).get("success")


def main():
    source["wait"]("source Gateway login", source["bootstrap"], seconds=180)
    peer["wait"]("isolated peer login at 9289", peer["bootstrap"], seconds=180)
    api = source["api"]
    kind = "ignition/gateway-network-outgoing"
    try:
        existing = api("GET", "/data/api/v1/resources/find/" + kind + "/camel-message-peer")
        assert existing["config"]["host"] == "message-peer" and existing["config"]["useSSL"], existing
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        api("POST", "/data/api/v1/resources/" + kind, [{"name": "camel-message-peer",
            "config": {"host": "message-peer", "port": 8060, "useSSL": True}}])
    # Approve only the peer certificates fetched through each authenticated local API.
    local = "/data/api/v1/gateway-network/certificates/localCert"
    approve_certificate(source, peer["api"]("GET", local)["digest"], "true")
    approve_certificate(peer, api("GET", local)["digest"], "false")
    def incoming():
        return next((entry for entry in peer["api"]("GET", "/data/api/v1/resources/list/ignition/gateway-network-incoming")["items"]
            if entry["config"]["connectionId"].startswith("camel-acceptance|")), None)
    connection = peer["wait"]("isolated incoming connection", incoming, seconds=90)
    remote_id = urllib.parse.quote(connection["config"]["connectionId"], safe="")
    peer["api"]("PUT", "/data/api/v1/gateway-network-incoming/toggleApproval/" + remote_id + "/true")
    source["wait"]("approved TLS Gateway Network connection", lambda: any(entry["remoteGateway"] == "camel-message-peer"
        and entry["status"] == "Running" for entry in api("GET", "/data/api/v1/gateway-network/connections")["items"]), seconds=90)
    provision_peer()
    source["delete"](ROUTE)
    routes = []
    for path, handler, mode in [("request", "echo", "request"), ("send", "notify", "send"), ("probe", "probe", "request")]:
        routes.append(f'''- route:
    from:
      uri: ignition-gateway-http:/{path}?httpMethodRestrict=POST
      steps:
        - to: ignition-message:{handler}?project={PROJECT}&gateway=camel-message-peer&mode={mode}&timeoutSec=5
''')
    source["create"](ROUTE, "\n".join(routes))
    endpoint = "/system/integrations/" + ROUTE
    source["wait"]("remote native handlers", lambda: api("POST", endpoint + "/request", {"number": 9007199254740993})
        == {"gateway": "camel-message-peer", "payload": {"number": 9007199254740993, "sendServerId": "camel-acceptance"}})
    payload = {"text": "cross-gateway-native-send"}
    assert api("POST", endpoint + "/send", payload) == payload
    source["wait"]("remote send side effect", lambda: api("POST", endpoint + "/probe", {}) == payload["text"])
    print("TLS-approved Gateway Network: Camel -> native remote request/reply and send -> remote handler/tag verified; peer requires no Camel module")


if __name__ == "__main__":
    main()
