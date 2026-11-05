"""Native Ignition 8.3.8 tag glob delivery/lifecycle on isolated port 9288.

Owns unique temporary routes, project and tag folder; never edits demo state.
"""
import json
import runpy
import time
import uuid
from pathlib import Path


def main():
    m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
    m["bootstrap"]()
    token = uuid.uuid4().hex[:10]
    project = "camel_glob_" + token
    tools = "camel-glob-tools-" + token
    route = "camel-glob-source-" + token
    root = "[default]CamelGlobAcceptance" + token
    source = '''from java.util.concurrent import ConcurrentLinkedQueue
ROOT = %r
events = ConcurrentLinkedQueue()

def record(payload, headers):
    events.add({"pattern": headers["probePattern"], "path": headers["ignitionTagPath"],
                "value": payload, "initial": headers["ignitionTagInitial"]})
    return payload

def action(payload, headers):
    operation = payload.get("operation")
    if operation == "events":
        return list(events)
    if operation == "create":
        path = ROOT + "/" + payload["path"]
        parts = path.split("/")
        parent = "[default]"
        for part in [parts[0][len("[default]"):]] + parts[1:-1]:
            if not system.tag.exists(parent + "/" + part):
                system.tag.configure(parent, [{"name": part, "tagType": "Folder"}], "a")
            parent += "/" + part
        results = system.tag.configure(parent, [{"name": parts[-1], "tagType": "AtomicTag",
            "valueSource": "memory", "dataType": "Int4", "value": 0}], "a")
    elif operation == "write":
        results = system.tag.writeBlocking([ROOT + "/" + payload["path"]], [payload["value"]])
    elif operation == "cleanup":
        results = system.tag.deleteTags([ROOT])
    else:
        raise Exception("Unknown probe operation")
    if not all(result.isGood() for result in results):
        raise Exception("Probe tag operation failed")
    return "ok"
''' % root
    action_uri = "ignition-script:routes.action?project=" + project
    tool_yaml = """
- route:
    from:
      uri: ignition-gateway-http:/action?httpMethodRestrict=POST
      steps:
        - to: %s
""" % action_uri
    def action(operation, **values):
        return m["api"]("POST", "/system/integrations/" + tools + "/action", {"operation": operation, **values})
    def events():
        return action("events")
    def await_delivery(path, expected):
        serial = [100]
        def delivered():
            serial[0] += 1
            action("write", path=path, value=serial[0])
            observed = {e["pattern"] for e in events() if e["path"] == root + "/" + path}
            assert observed.issubset(expected), (path, observed, expected)
            return observed == expected
        try:
            m["wait"]("native subscription for " + path, delivered, seconds=20)
        except AssertionError:
            print("Observed:", events())
            print("Details:", json.dumps(m["api"]("GET", m["MODULE"] + "/camel-route-details?name=" + route)))
            raise
    try:
        m["import_project"](project, source)
        m["create"](tools, tool_yaml)
        m["wait"]("probe tools", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(tools) == "RUNNING", seconds=30)
        m["wait"]("probe project scripting manager", lambda: action("events") == [], seconds=30)
        for path in ("existing/press-1/Complete", "existing/press-12/Complete", "existing/deep/press-2/Complete", "existing/press-1/Other", "existing/Complete"):
            assert action("create", path=path) == "ok"
        # Deliberately no exact-path source here: it would warm the prefix and
        # mask 8.3.8's missing-existing-subscription-node cold-start defect.
        patterns = {
            "single": "ignition-tag:" + root + "/existing/*/Complete",
            "recursive": "ignition-tag:" + root + "/existing/**/Complete",
            "question": "ignition-tag:watch?path=RAW(" + root + "/existing/press-?/Complete)",
        }
        yaml = "\n".join("""
- route:
    id: glob-%s
    from:
      uri: '%s'
      steps:
        - setHeader:
            name: probePattern
            constant: '%s'
        - to: ignition-script:routes.record?project=%s
""" % (name, uri, name, project) for name, uri in patterns.items())
        m["create"](route, yaml)
        m["wait"]("native glob sources", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "RUNNING", seconds=30)
        await_delivery("existing/press-1/Complete", {"single", "recursive", "question"})
        await_delivery("existing/press-12/Complete", {"single", "recursive"})
        await_delivery("existing/deep/press-2/Complete", {"recursive"})
        await_delivery("existing/Complete", {"recursive"})
        # Create matching tags after startup: no browse loop or route restart.
        action("create", path="existing/press-3/Complete")
        await_delivery("existing/press-3/Complete", {"single", "recursive", "question"})
        action("create", path="existing/new/deep/press-4/Complete")
        await_delivery("existing/new/deep/press-4/Complete", {"recursive"})
        action("write", path="existing/press-1/Other", value=777)
        assert not any(e["path"].endswith("/Other") or e["initial"] for e in events())
        details = m["api"]("GET", m["MODULE"] + "/camel-route-details?name=" + route)
        assert details["metrics"]["failed"] == 0, details
        m["delete"](route)
        count = len(events())
        action("write", path="existing/press-1/Complete", value=999)
        time.sleep(1)
        assert len(events()) == count, "Stopped consumers still delivered events"
        # Recreate the sources to prove close/restart reattaches cleanly.
        m["create"](route, yaml)
        m["wait"]("reattached glob sources", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "RUNNING", seconds=30)
        m["wait"]("post-restart event", lambda: action("write", path="existing/press-1/Complete", value=1000 + len(events())) == "ok" and len(events()) > count, seconds=15)
        print("Verified cold-prefix native *, **, ?, existing/new tags, actual paths, initial suppression, unsubscribe and restart.")
    finally:
        m["delete"](route)
        try:
            if m["find"](tools):
                action("cleanup")
        finally:
            m["delete"](tools)
            m["api"]("DELETE", "/data/api/v1/projects/" + project)


if __name__ == "__main__":
    main()
