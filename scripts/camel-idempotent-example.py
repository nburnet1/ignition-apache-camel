"""Install and verify the native Idempotent Consumer demo on isolated port 9288."""
import json
import runpy
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(Path(__file__).with_name("camel-workorder-example.py")))
api, wait = m["api"], m["wait"]
NAME = "camel-example-idempotent-consumer"
TOOLS = "camel-example-idempotent-tools"
REPOSITORY = "idem-message-ids"
TOOL_YAML = "\n".join("""
- route:
    from:
      uri: ignition-gateway-http:/%s?httpMethodRestrict=POST
      steps:
        - to: ignition-script:idempotent_consumer.%s?project=CamelExamples
""" % (operation, operation) for operation in ("setup", "snapshot", "writeInput"))


def tool(operation, payload=None):
    return api("POST", "/system/integrations/" + TOOLS + "/" + operation, payload or {})


def submit(payload):
    return api("POST", "/system/integrations/" + NAME + "/submit", payload)


def main():
    wait("isolated Gateway", m["m"]["bootstrap"], seconds=180)
    m["project"]["ensure_project"](m["m"], ("idempotent_consumer",))
    assert m["find"](m["m"]["TYPE"], TOOLS) is None, "Review the existing temporary Idempotent Consumer tools resource"
    m["ensure"](m["m"]["TYPE"], TOOLS, {"yaml": TOOL_YAML})
    try:
        wait("idempotent tools", lambda: m["running"](TOOLS))
        wait("example tag setup", lambda: tool("setup") == "ready", seconds=40)
        m["ensure"](m["REGISTRY"], REPOSITORY, {"type": "BEAN", "bean": {
            "project": "CamelExamples", "functionPath": "idempotent_consumer.repositoryFactory", "arguments": {"capacity": "1000"}}})
        m["ensure"](m["m"]["TYPE"], NAME, {
            "yaml": (ROOT / "examples/camel/idempotent-consumer.yaml").read_text(),
            "registry": [REPOSITORY]})
        wait("native Idempotent Consumer", lambda: m["running"](NAME))
        live = m["find"](m["m"]["TYPE"], NAME)["config"]
        assert live.get("registry", []) == [REPOSITORY] and not live.get("components") \
            and "idem-observer" not in live["yaml"] and "some-queue" not in live["yaml"], \
            "Older installed example: update its YAML and keep only the idem-message-ids Registry dependency"
        prefix = "IDEM-" + uuid.uuid4().hex[:8]
        first = {"id": prefix + "-A", "value": "first delivery"}
        assert submit(first) == "delivered"
        assert submit({"id": first["id"], "value": "changed content, same ID"}) == "skipped"
        report = tool("snapshot")
        assert json.loads(report["LastDelivered"]) == first
        assert report["LastResult"] == "skipped"
        assert submit({"id": prefix + "-B", "value": {"quantity": 7}}) == "delivered"
        tagged = {"id": prefix + "-C", "value": "submitted from the Input tag"}
        assert tool("writeInput", tagged) == "written"
        wait("tag input delivered", lambda: json.loads(tool("snapshot")["LastDelivered"]) == tagged)
        assert submit(tagged) == "skipped", "Tag and HTTP ingress must share the same repository"
        report = tool("snapshot")
        assert report["LastResult"] == "skipped", report
        details = api("GET", m["MODULE"] + "/camel-route-details?name=" + NAME)
        assert details["metrics"]["failed"] == 0, details
        assert any(step["kind"] == "idempotentConsumer" for route in details["routes"] for step in route["steps"]), details
        print(json.dumps(report, indent=2))
        print("Verified duplicate suppression through HTTP and tag inputs; LastDelivered changes only for new IDs. Left running on 9288.")
    finally:
        m["m"]["delete"](TOOLS)


if __name__ == "__main__":
    main()
