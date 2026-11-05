"""Read-only diagnostics/dependency/deployment checks on 9288 using isolated temporary fixtures."""
import copy
import json
from pathlib import Path
import runpy
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
    m["wait"]("9288 login", m["bootstrap"], seconds=90)
    prefix = "op-" + uuid.uuid4().hex[:10]
    route, action, component, tls = [prefix + suffix for suffix in ("-route", "-action", "-http", "-tls")]
    module_type = "com.noahburnette.ignition.apachecamel/"
    created = []
    original = m["api"]("GET", m["MODULE"] + "/camel-route-diagnostics")

    def find(kind, name):
        try:
            return m["api"]("GET", "/data/api/v1/resources/find/" + module_type + kind + "/" + name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise

    def create(kind, name, config):
        m["api"]("POST", "/data/api/v1/resources/" + module_type + kind, [{"name": name, "enabled": True, "config": config}])
        created.append((kind, name))

    def details():
        return m["api"]("GET", m["MODULE"] + "/camel-route-details?name=" + route)

    def dependencies(kind, name):
        return m["api"]("GET", m["MODULE"] + "/camel-dependencies?" + urllib.parse.urlencode({"kind": kind, "name": name}))

    def manifest():
        return m["api"]("GET", m["MODULE"] + "/camel-deployment-manifest")

    def compare(source):
        return m["api"]("POST", m["MODULE"] + "/camel-deployment-compare", source)

    try:
        create("camel-registry", tls, {"type": "TLS", "tls": {}})
        create("camel-component", component, {"type": "HTTPS", "http": {"tls": tls}})
        create("camel-kamelet", action, {"yaml": """apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: ignored
spec:
  definition:
    title: Probe
    properties:
      token:
        type: string
        default: private-default-must-not-be-exported
  template:
    from:
      uri: kamelet:source
      steps:
        - to: kamelet:sink
"""})
        path = "/operations-test/" + prefix
        create("camel-route", route, {"components": [component], "kamelets": [action], "httpSecurityLevels": ["Authenticated/Roles/Administrator"], "yaml": """- route:
    id: operations-probe
    from:
      uri: ignition-gateway-http:%s?httpMethodRestrict=POST
      steps:
        - setBody:
            simple: '${body[mode]}'
        - to: kamelet:%s/probe-action
        - choice:
            when:
              - simple: "${body} == 'fail'"
                steps:
                  - throwException:
                      exceptionType: java.lang.IllegalStateException
                      message: private-exception-must-not-be-exported
            otherwise:
              steps:
                - setBody:
                    constant: recovered
""" % (path, action)})
        m["wait"]("operations fixture", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "RUNNING", seconds=30)

        before = manifest()
        assert before["moduleVersion"] != "unknown"
        assert before["libraries"] and all(len(row["sha256"]) == 64 for row in before["libraries"])
        assert any(row.get("version") == "4.22.2" for row in before["libraries"])
        graph = dependencies("route", route)
        assert any(row["name"] == tls and row["relationship"] == "Transitive" for row in graph["requires"]), graph
        assert any(row["name"] == route for row in dependencies("registry", tls)["usedBy"])
        assert any(row["name"] == route for row in dependencies("kamelet", action)["usedBy"])
        assert dependencies("kamelet", action)["resource"]["parameters"][0]["hasDefault"] is True

        for _ in range(2):
            try:
                m["api"]("POST", "/system/integrations" + path, {"mode": "fail", "privateInput": "private-body-must-not-be-retained"})
            except urllib.error.HTTPError as error:
                assert error.code >= 500, error.code
            else:
                raise AssertionError("Fixture should have failed")
        m["wait"]("failure diagnostics", lambda: details()["diagnosticSummary"]["status"] == "ATTENTION", seconds=10)
        failed = details()
        assert failed["recentFailures"] and all("type" in row and "timestamp" in row for row in failed["recentFailures"])
        assert any(row.get("consecutiveFailures") == 2 for row in failed["routes"])
        assert m["api"]("POST", "/system/integrations" + path, {"mode": "ok"}) == "recovered"
        m["wait"]("success clears current-failure signal", lambda: details()["diagnosticSummary"]["status"] == "NO_CURRENT_FAILURE_SIGNAL", seconds=10)
        recovered = details()
        assert recovered["recentFailures"], "Historical metadata should remain after recovery"
        assert any(row.get("lastSucceededAt", 0) > 0 for row in recovered["routes"])

        same = compare(before)
        assert same["blockingMismatch"] is False, same
        bad_library = copy.deepcopy(before)
        bad_library["libraries"][0]["sha256"] = "0" * 64
        result = compare(bad_library)
        assert result["blockingMismatch"] and any(row["code"] == "LIBRARY_MISMATCH" for row in result["errors"]), result
        bad_config = copy.deepcopy(before)
        next(row for row in bad_config["resources"] if row["kind"] == "route" and row["name"] == route)["fingerprint"] = "0" * 64
        assert any(row["code"] == "CONFIGURATION_MISMATCH" for row in compare(bad_config)["errors"])

        for path_suffix in ("/camel-deployment-manifest", "/camel-dependencies?kind=route&name=" + route):
            try:
                urllib.request.urlopen(m["BASE"] + m["MODULE"] + path_suffix, timeout=15)
            except urllib.error.HTTPError as error:
                assert error.code in (401, 403), error.code
            else:
                raise AssertionError("Anonymous operations access accepted")
        request = urllib.request.Request(m["BASE"] + m["MODULE"] + "/camel-deployment-compare", method="POST",
            data=json.dumps(before).encode(), headers={"Content-Type": "application/json"})
        try:
            m["HTTP"].open(request, timeout=15)
        except urllib.error.HTTPError as error:
            assert error.code == 403, error.code
        else:
            raise AssertionError("Manifest compare accepted without CSRF")
        try:
            compare({"schemaVersion": 999})
        except urllib.error.HTTPError as error:
            assert error.code == 400, error.code
        else:
            raise AssertionError("Malformed manifest accepted")

        after = manifest()
        assert [(r["kind"], r["name"], r["fingerprint"]) for r in before["resources"]] == [(r["kind"], r["name"], r["fingerprint"]) for r in after["resources"]]
        assert before["libraries"] == after["libraries"], "Read-only comparison changed libraries"
        all_metadata = json.dumps([before, graph, failed, recovered, dependencies("kamelet", action)])
        for private in ("private-default-must-not-be-exported", "private-exception-must-not-be-exported", "private-body-must-not-be-retained"):
            assert private not in all_metadata, private
        current = m["api"]("GET", m["MODULE"] + "/camel-route-diagnostics")
        for name, value in original.items():
            assert current.get(name, {}).get("generation") == value.get("generation"), "Operations inspection restarted " + name
        print("Verified failure/recovery metadata (no payloads), dependency/reverse/transitive views, manifest export/preflight, checksum/config mismatches, READ/CSRF enforcement, unchanged configuration/libraries and unrelated runtime generations.")
    finally:
        for kind, name in reversed(created):
            existing = find(kind, name)
            if existing:
                m["api"]("POST", "/data/api/v1/resources/delete/" + module_type + kind, [{"name": name, "signature": existing["signature"]}])


if __name__ == "__main__":
    main()
