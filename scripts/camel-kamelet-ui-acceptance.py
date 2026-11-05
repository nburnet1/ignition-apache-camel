"""Native YAML/Jython Kamelet save/reload/lifecycle acceptance on the isolated 9288 Gateway.

Uses unique temporary resources/project. No extension upload or Gateway restart.
"""
import json
from pathlib import Path
import runpy
import urllib.error
import urllib.request
import uuid


def main():
    m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
    m["wait"]("9288 login", m["bootstrap"], seconds=90)
    kind = "com.noahburnette.ignition.apachecamel/camel-kamelet"
    token = uuid.uuid4().hex[:10]
    child, parent = "kt-child-" + token, "kt-parent-" + token
    unused = "kt-unused-" + token
    route = "kt-caller-" + token
    project = "kamelet_test_" + token
    path = "/kamelet-test/" + token
    names = []
    original = m["api"]("GET", m["MODULE"] + "/camel-route-diagnostics")

    def find(name):
        try:
            return m["api"]("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise

    def create(name, config):
        m["api"]("POST", "/data/api/v1/resources/" + kind, [{"name": name, "enabled": True, "config": config}])
        if name not in names:
            names.append(name)

    def modify(name, **changes):
        existing = find(name)
        m["api"]("PUT", "/data/api/v1/resources/" + kind,
                 [{"name": name, "signature": existing["signature"], **changes}])

    def delete(name):
        existing = find(name)
        if existing:
            m["api"]("POST", "/data/api/v1/resources/delete/" + kind,
                     [{"name": name, "signature": existing["signature"]}])

    def yaml(prefix="Hello "):
        return """apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: source-name-is-not-the-resource-id
spec:
  definition:
    title: Test greeting
    properties:
      prefix:
        type: string
        default: %s
  template:
    from:
      uri: kamelet:source
      steps:
        - setBody:
            simple: '{{prefix}}${body}'
        - to: kamelet:sink
""" % json.dumps(prefix)

    def config(source, dependencies=None):
        return {"language": "YAML", "yaml": source, "kamelets": dependencies or []}

    def generation():
        return m["diagnostics"](route).get("generation", 0)

    def running():
        return m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "RUNNING"

    def call():
        return m["api"]("POST", "/system/integrations" + path, {"value": "Camel"})

    def changed_and_returns(before, expected):
        m["wait"]("targeted reload to " + expected,
                  lambda: running() and generation() != before and call() == expected, seconds=30)

    try:
        # Native resource writes must retain SDK CSRF protection.
        request = urllib.request.Request(m["BASE"] + "/data/api/v1/resources/" + kind,
            method="POST", data=b"[]", headers={"Content-Type": "application/json"})
        try:
            m["HTTP"].open(request, timeout=15)
        except urllib.error.HTTPError as error:
            assert error.code == 403, error.code
        else:
            raise AssertionError("Native Kamelet write accepted without CSRF")
        try:
            create(child, config("- route:\n    from: direct:not-a-kamelet"))
        except urllib.error.HTTPError as error:
            assert error.code in (400, 422), error.code
            assert "yaml" in error.read().decode(), "Validation should identify the YAML field"
        else:
            raise AssertionError("A running route was accepted as a Kamelet definition")

        create(child, config(yaml()))
        assert "config.kamelet.yaml" in find(child)["data"], "YAML is not in the native file manifest"
        parent_yaml = """apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: ignored
spec:
  definition:
    title: Nested action
  template:
    from:
      uri: kamelet:source
      steps:
        - to: kamelet:%s/nested-child
        - to: kamelet:sink
""" % child
        create(parent, config(parent_yaml, [child]))
        m["api"]("POST", "/data/api/v1/resources/" + m["TYPE"], [{
            "name": route, "enabled": True, "config": {"yaml": """- route:
    from:
      uri: ignition-gateway-http:%s?httpMethodRestrict=POST
      steps:
        - setBody:
            simple: '${body[value]}'
        - to: kamelet:%s/caller
""" % (path, parent), "kamelets": [parent], "httpSecurityLevels": ["Authenticated/Roles/Administrator"]}}])
        m["wait"]("native nested Kamelet execution", lambda: running() and call() == "Hello Camel", seconds=30)
        before = generation()
        modify(child, config=config(yaml("Updated ")))
        changed_and_returns(before, "Updated Camel")
        before = generation()
        modify(child, enabled=False)
        m["wait"]("disabled dependency stops caller", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "ERROR", seconds=20)
        modify(child, enabled=True)
        changed_and_returns(before, "Updated Camel")
        delete(child)
        m["wait"]("deleted dependency stops caller", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(route) == "ERROR", seconds=20)
        create(child, config(yaml()))
        m["wait"]("recreated dependency recovers", lambda: running() and call() == "Hello Camel", seconds=30)

        # Saving a Jython reference must not look up/import/invoke its project function.
        before = generation()
        create(unused, {"language": "JYTHON", "project": "does-not-exist", "functionPath": "missing.neverRun", "kamelets": []})
        assert generation() == before, "An unrelated definition restarted the caller"
        source = """def build(builder):
    builder.template().templateParameter("prefix", "Jython ")
    builder.from_("kamelet:source").setBody().simple("{{prefix}}${body}").to("kamelet:sink")
"""
        m["import_project"](project, source)
        before = generation()
        modify(child, config={"language": "JYTHON", "yaml": yaml(), "project": project, "functionPath": "routes.build", "kamelets": []})
        changed_and_returns(before, "Jython Camel")
        # The inactive YAML draft is retained in the native file, not executed.
        assert find(child)["config"]["yaml"] == yaml()
        before = generation()
        m["import_project"](project, source.replace("Jython ", "Reloaded "))
        changed_and_returns(before, "Reloaded Camel")

        current = m["api"]("GET", m["MODULE"] + "/camel-route-diagnostics")
        for name, diagnostics in original.items():
            assert current.get(name, {}).get("generation") == diagnostics.get("generation"), "Unrelated route was restarted: " + name
        print("Verified native YAML files, CSRF, structural validation, nested definitions, targeted reload, disable/delete recovery, Jython DSL and project-script reload; unrelated routes unchanged.")
    finally:
        m["delete"](route)
        for name in reversed(names):
            delete(name)
        try:
            m["api"]("DELETE", "/data/api/v1/projects/" + project)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise


if __name__ == "__main__":
    main()
