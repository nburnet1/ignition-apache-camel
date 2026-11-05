"""Three-section Camel configuration acceptance on the isolated Gateway only."""
import json
import runpy
import sys
import urllib.error
import urllib.request
from pathlib import Path

m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
api, wait = m["api"], m["wait"]
SECRETS = "camel-configuration-secrets"
MODULE = m["MODULE"]
COMPONENT = "com.noahburnette.ignition.apachecamel/camel-component"
REGISTRY = "com.noahburnette.ignition.apachecamel/camel-registry"
ROUTE = "camel-acceptance-sections"
ENDPOINT = "/system/integrations/" + ROUTE
PROJECT = "camel_sections_factories"
FACTORIES = '''from java.util.concurrent.atomic import AtomicInteger
from java.lang import AutoCloseable

def counter(config):
    if config["credential"] != "configuration-secret-proof":
        raise Exception("Referenced secret did not resolve")
    return AtomicInteger(int(config["start"]))

class CloseProbe(AutoCloseable):
    def close(self):
        path = "[default]CamelSectionsCloses"
        old = system.tag.readBlocking([path])[0].value
        system.tag.writeBlocking([path], [int(old or 0) + 1])

def closeProbe(config):
    system.tag.configure("[default]", [{"name":"CamelSectionsCloses", "tagType":"AtomicTag", "valueSource":"memory", "dataType":"Int4"}], "m")
    return CloseProbe()

def closes(payload, headers):
    return int(system.tag.readBlocking(["[default]CamelSectionsCloses"])[0].value or 0)

def observe(payload, headers):
    system.tag.configure("[default]", [{"name":"CamelSectionsMqtt", "tagType":"AtomicTag", "valueSource":"memory", "dataType":"Int4", "value":int(payload["number"])}], "m")
    return payload

def snapshot(payload, headers):
    return int(system.tag.readBlocking(["[default]CamelSectionsMqtt"])[0].value or 0)

def databaseRoute(builder):
    source = builder.getContext().getRegistry().lookupByName("proof-database")
    connection = source.getConnection()
    try:
        statement = connection.createStatement()
        try:
            result = statement.executeQuery("SELECT 42")
            try:
                result.next()
                answer = result.getInt(1)
            finally:
                result.close()
        finally:
            statement.close()
    finally:
        connection.close()
    (builder.from_("ignition-gateway-http:/camel-acceptance-database-binding/query?httpMethodRestrict=POST")
        .routeId("database-binding-proof").setBody().constant(answer))
'''
YAML = '''
- route:
    id: sections-next
    from:
      uri: ignition-gateway-http:/next?httpMethodRestrict=POST
      steps:
        - to: bean:proof-counter?method=incrementAndGet
- route:
    id: sections-closes
    from:
      uri: ignition-gateway-http:/closes?httpMethodRestrict=POST
      steps:
        - to: ignition-script:routes.closes?project=camel_sections_factories
- route:
    id: sections-file
    from:
      uri: ignition-gateway-http:/file?httpMethodRestrict=POST
      steps:
        - setBody:
            simple: '{{fileText}}'
        - to: file:{{directory}}?fileName=sections.txt&fileExist=Override&autoCreate=true
        - setBody:
            constant: written
- route:
    id: sections-clock
    from:
      uri: proof-clock:sections/tick?trigger.repeatInterval=1000&trigger.repeatCount=-1
      steps:
        - setBody:
            constant: tick
- route:
    id: sections-publish
    from:
      uri: ignition-gateway-http:/publish?httpMethodRestrict=POST
      steps:
        - marshal:
            json:
              library: Jackson
        - to: proof-mqtt:apache-camel/camel/sections?clientId=sections-producer&retained=true
        - setBody:
            constant: published
- route:
    id: sections-consume
    from:
      uri: proof-mqtt:apache-camel/camel/sections?clientId=sections-consumer
      steps:
        - convertBodyTo:
            type: java.lang.String
        - unmarshal:
            json:
              library: Jackson
        - to: ignition-script:routes.observe?project=camel_sections_factories
- route:
    id: sections-snapshot
    from:
      uri: ignition-gateway-http:/snapshot?httpMethodRestrict=POST
      steps:
        - to: ignition-script:routes.snapshot?project=camel_sections_factories
'''


def find(kind, name):
    try:
        return api("GET", "/data/api/v1/resources/find/" + kind + "/" + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise


def modify(kind, name, **fields):
    current = find(kind, name)
    api("PUT", "/data/api/v1/resources/" + kind, [{"name": name, "signature": current["signature"], **fields}])


def fixtures():
    if not find("ignition/secret-provider", SECRETS):
        token = api("GET", "/data/app/session")["csrfToken"]
        def encrypt(value):
            request = urllib.request.Request(m["BASE"] + "/data/api/v1/encryption/encrypt", data=value.encode(),
                headers={"Content-Type": "text/plain", "X-CSRF-Token": token}, method="POST")
            with m["HTTP"].open(request, timeout=15) as response:
                return json.load(response)
        api("POST", "/data/api/v1/resources/ignition/secret-provider", [{"name": SECRETS,
            "config": {"profile": {"type": "internal"}, "settings": {"secrets": {
                "credential": {"ciphertext": encrypt("configuration-secret-proof")},
                "database": {"ciphertext": encrypt("camel-acceptance-only")}}}}}])
    if not find("ignition/database-connection", "CamelAcceptance"):
        api("POST", "/data/api/v1/resources/ignition/database-connection", [{"name": "CamelAcceptance", "config": {
            "driver": "PostgreSQL", "translator": "POSTGRES", "connectURL": "jdbc:postgresql://postgres:5432/camel",
            "username": "camel", "password": {"type": "Referenced", "data": {"providerName": SECRETS, "secretName": "database"}}, "poolMaxActive": 12}}])


def ensure(kind, name, config):
    if kind == m["TYPE"]:
        config = {"httpSecurityLevels": ["Authenticated/Roles/Administrator"], **config}
    if find(kind, name):
        modify(kind, name, config=config, enabled=True)
    else:
        api("POST", "/data/api/v1/resources/" + kind, [{"name": name, "config": config}])


def generation():
    return m["diagnostics"](ROUTE).get("generation", 0)


def running():
    return api("GET", MODULE + "/camel-routes").get(ROUTE) == "RUNNING"


def fired():
    details = api("GET", MODULE + "/camel-route-details?name=" + ROUTE)
    return next((route.get("completed", 0) for route in details.get("routes", []) if route["id"] == "sections-clock"), 0)


def main():
    wait("Gateway login", m["bootstrap"], seconds=180)
    if "--verify-restart" in sys.argv:
        wait("three-section routes restored", running)
        assert api("POST", ENDPOINT + "/next", {}) == 101
        assert api("POST", ENDPOINT + "/publish", {"number": 712}) == "published"
        wait("configured MQTT receives after restart", lambda: api("POST", ENDPOINT + "/snapshot", {}) == 712)
        wait("in-memory Quartz schedule recreated after restart", lambda: fired() > 0)
        assert api("POST", "/system/integrations/camel-acceptance-database-binding/query", {}) == 42
        print("  Components/Registry selections, secret-backed beans, named MQTT/Quartz and Ignition DataSource recovered after Gateway restart")
        return
    navigation = json.dumps(api("GET", "/data/app/navigation"))
    for section in ("integrations", "library", "extensions"):
        assert "/services/apache-camel/" + section in navigation
    fixtures()
    for route in (ROUTE, "camel-acceptance-database-binding"):
        m["delete"](route)
    m["import_project"](PROJECT, FACTORIES)
    registry = {
        "proof-counter": {"type": "BEAN", "bean": {"project": PROJECT, "functionPath": "routes.counter", "arguments": {"credential": "{{secret.credential}}", "start": "{{start}}"}}},
        "proof-close": {"type": "BEAN", "bean": {"project": PROJECT, "functionPath": "routes.closeProbe"}},
        "proof-tls": {"type": "TLS", "tls": {}},
        "proof-database": {"type": "DATABASE", "database": "CamelAcceptance"},
    }
    for name, config in registry.items():
        ensure(REGISTRY, name, config)
    components = {
        "proof-https": {"type": "HTTPS", "http": {"tls": "proof-tls"}},
        "proof-mqtt": {"type": "MQTT5", "mqtt": {"brokerUrl": "tcp://mqtt:1883", "qos": 1, "connectionTimeout": 5, "cleanStart": True, "sessionExpiryInterval": 0}},
        "proof-clock": {"type": "QUARTZ", "quartz": {"threads": 2}},
    }
    for name, config in components.items():
        ensure(COMPONENT, name, config)
    for reserved in ("platform-http", "ignition-tag", "properties", "Not-a-scheme"):
        try:
            api("POST", "/data/api/v1/resources/" + COMPONENT, [{"name": reserved, "config": components["proof-https"]}])
            raise AssertionError("Reserved/invalid component name accepted")
        except urllib.error.HTTPError as error:
            # Ignition maps failed structural resource actions to 500 as well as validation errors.
            assert error.code in (400, 422, 500)
            assert find(COMPONENT, reserved) is None
    options = api("GET", MODULE + "/camel-configuration-options")
    assert any(value["name"] == "proof-counter" for value in options["registry"])
    config = {"yaml": YAML, "httpSecurityLevels": ["Authenticated/Roles/Administrator"],
        "components": list(components), "registry": ["proof-counter", "proof-close"],
        "properties": {"start": "40", "directory": "/usr/local/bin/ignition/data/camel-sections-proof", "fileText": "sections-file-proof"},
        "secrets": {"credential": {"providerName": SECRETS, "secretName": "credential"}}}
    api("POST", "/data/api/v1/resources/" + m["TYPE"], [{"name": ROUTE, "config": config}])
    wait("separate component and registry setup", running)
    assert api("POST", ENDPOINT + "/next", {}) == 41
    assert api("POST", ENDPOINT + "/file", {}) == "written"
    assert api("POST", ENDPOINT + "/publish", {"number": 711}) == "published"
    wait("configured MQTT producer/consumer", lambda: api("POST", ENDPOINT + "/snapshot", {}) == 711)
    wait("configured in-memory Quartz trigger", lambda: fired() > 0)
    unrelated = "camel-acceptance-unrelated"
    ensure(m["TYPE"], unrelated, {"yaml": "- route:\n    from:\n      uri: direct:unrelated\n      steps:\n        - setBody:\n            constant: unchanged\n"})
    wait("unrelated route startup", lambda: m["diagnostics"](unrelated).get("generation", 0) > 0)
    retained = m["diagnostics"](unrelated)["generation"]
    before, closes = generation(), api("POST", ENDPOINT + "/closes", {})
    components["proof-mqtt"]["mqtt"]["qos"] = 2
    modify(COMPONENT, "proof-mqtt", config=components["proof-mqtt"])
    wait("component change targeted rebuild", lambda: generation() > before and running())
    assert api("POST", ENDPOINT + "/closes", {}) == closes + 1
    assert m["diagnostics"](unrelated)["generation"] == retained
    before = generation()
    registry["proof-counter"]["bean"]["arguments"]["start"] = "100"
    modify(REGISTRY, "proof-counter", config=registry["proof-counter"])
    wait("registry change targeted rebuild", lambda: generation() > before and running())
    assert api("POST", ENDPOINT + "/next", {}) == 101
    before = generation()
    registry["proof-tls"]["tls"]["protocol"] = "TLSv1.2"
    modify(REGISTRY, "proof-tls", config=registry["proof-tls"])
    wait("transitive component TLS dependency rebuild", lambda: generation() > before and running())
    assert m["diagnostics"](unrelated)["generation"] == retained
    before = generation()
    m["import_project"](PROJECT, FACTORIES + "\n# targeted sections factory reload\n")
    wait("registry factory project rebuild", lambda: generation() > before and running())
    for kind, name in [(COMPONENT, "proof-mqtt"), (REGISTRY, "proof-counter")]:
        for enabled, state in [(False, "ERROR"), (True, "RUNNING")]:
            modify(kind, name, enabled=enabled)
            wait("dependency " + state, lambda: api("GET", MODULE + "/camel-routes").get(ROUTE) == state)
    api("POST", "/data/api/v1/resources/" + m["TYPE"], [{"name": "camel-acceptance-database-binding", "config": {
        "language": "JYTHON", "project": PROJECT, "functionPath": "routes.databaseRoute", "registry": ["proof-database"],
        "httpSecurityLevels": ["Authenticated/Roles/Administrator"]}}])
    wait("Ignition DataSource registry binding", lambda: api("GET", MODULE + "/camel-routes").get("camel-acceptance-database-binding") == "RUNNING")
    assert api("POST", "/system/integrations/camel-acceptance-database-binding/query", {}) == 42
    print("  native Routes/Components/Registry resources, typed validation, secret-backed beans/cleanup, named MQTT/Quartz, transitive TLS reloads and Ignition DataSource verified")


if __name__ == "__main__":
    main()
