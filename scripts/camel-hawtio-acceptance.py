#!/usr/bin/env python3
"""Real Gateway security and lifecycle checks on the dedicated 9488 Hawtio fixture."""
import importlib.util
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid


def client():
    spec = importlib.util.spec_from_file_location("camel_acceptance", Path(__file__).with_name("camel-acceptance.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.BASE = "http://localhost:9488"
    return module


def main():
    c = client()
    c.wait("Hawtio fixture login", c.bootstrap)
    endpoint = c.MODULE + "/camel-jolokia"
    version = c.api("GET", endpoint + "/version")
    assert version["status"] == 200 and version["value"]["agent"] == "2.6.4", version

    def denied(opener, headers=None):
        request = urllib.request.Request(c.BASE + endpoint, method="POST", data=b'{"type":"version"}',
                                         headers={"Content-Type": "application/json", **(headers or {})})
        try:
            opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            assert error.code in (401, 403), error.code
            error.close()
        else:
            raise AssertionError("Unauthenticated or CSRF-less management access was accepted")

    denied(urllib.request.build_opener())
    denied(c.HTTP)  # Logged-in session, deliberately no CSRF token.
    denied(c.HTTP, {"X-CSRF-Token": c.CSRF, "Origin": "https://untrusted.invalid"})
    names = ["hawtio-check-" + uuid.uuid4().hex[:12] for _ in range(2)]
    source = """- route:
    id: fixture-timer
    from:
      uri: timer:hawtio-fixture?period=100
      steps:
        - setBody:
            constant: test
"""
    try:
        for name in names:
            c.create(name, source)
            c.wait("running " + name, lambda: c.api("GET", c.MODULE + "/camel-routes").get(name) == "RUNNING")

        def post(body):
            return c.api("POST", endpoint, body)

        all_beans = post({"type": "search", "mbean": "*:*"})["value"]
        assert "java.lang:type=Memory" in all_beans, "JVM-readonly scope was not explicitly enabled"
        owned = [bean for bean in all_beans if "integration-" + names[0] in bean and "type=routes" in bean]
        assert len(owned) == 1, all_beans
        bean = owned[0]
        assert post({"type": "read", "mbean": "java.lang:type=Memory", "attribute": "HeapMemoryUsage"})["status"] == 200
        metadata = json.dumps(post({"type": "list", "path": "org.apache.camel", "config": {"maxDepth": 9, "listCache": True}}))
        assert '"sendBody":' not in metadata and '"stop":' not in metadata and "dumpRoutesAsXml" in metadata
        assert post({"type": "exec", "mbean": bean, "operation": "remove()"})["status"] != 200
        assert post({"type": "exec", "mbean": "java.lang:type=Memory", "operation": "gc()"})["status"] != 200

        def state(name):
            details = c.api("GET", c.MODULE + "/camel-route-details?name=" + name)
            assert details["hawtioEnabled"] and not details["hawtioControlEnabled"], details
            assert details["hawtioScope"] == "jvm-readonly", details
            return details["routes"][0]["state"]

        for operation in ("stop()", "start()", "suspend()", "resume()", "reset()"):
            result = post({"type": "exec", "mbean": bean, "operation": operation, "arguments": []})
            assert result["status"] != 200, result
            assert state(names[0]) == "RUNNING", "A read-only control changed runtime state"
            assert state(names[1]) == "RUNNING", "A control affected another resource"

        try:
            post({"type": "write", "mbean": "java.lang:type=Memory", "attribute": "Verbose", "value": True})
        except urllib.error.HTTPError as error:
            assert error.code == 403, error.code
            error.close()
        else:
            raise AssertionError("Read-only mode accepted a write")
        context = next(value for value in all_beans if "integration-" + names[0] in value and "type=context" in value)
        source = post({"type": "exec", "mbean": context, "operation": "dumpRoutesAsXml()"})
        assert source["status"] == 200 and "fixture-timer" in source["value"], source
        statistics = post({"type": "exec", "mbean": context, "operation": "dumpRoutesStatsAsXml(boolean,boolean)", "arguments": [True, True]})
        assert statistics["status"] == 200 and "routeStat" in statistics["value"], statistics
        c.api("POST", c.MODULE + "/camel-route-restart?name=" + names[0], {})
        c.wait("resource replacement restores configured startup", lambda: state(names[0]) == "RUNNING")
        assert c.find(names[0])["enabled"], "Temporary controls changed persisted enabled state"
        print("Hawtio acceptance passed: native auth/CSRF, JVM discovery/reads, Camel XML/statistics and denied mutations")
    finally:
        for name in names:
            c.delete(name)
        remaining = c.api("POST", endpoint, {"type": "search", "mbean": "org.apache.camel:*"})["value"]
        assert not any(name in bean for bean in remaining for name in names), remaining


if __name__ == "__main__":
    main()
