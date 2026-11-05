"""Verify scoped native API-key invocation on the disposable 9288 Gateway.

Creates temporary security levels, keys and echo routes via native APIs and
removes them afterward. Gateway general access/read/write rules are NOT changed.
HTTP-enabled keys are strictly for this loopback-only development fixture.
"""
import copy
import argparse
import importlib.util
import json
from pathlib import Path
import time
import subprocess
import urllib.error
import urllib.request
import uuid


def main(restart=False):
    spec = importlib.util.spec_from_file_location(
        "camel_acceptance", Path(__file__).with_name("camel-acceptance.py"))
    gateway = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gateway)
    assert gateway.BASE == "http://localhost:9288"
    assert gateway.bootstrap(), "Disposable Gateway login failed"
    prefix = "http-key-probe-" + uuid.uuid4().hex[:12]
    created = []
    results = []
    levels_original = None
    levels_applied = None
    general_original = gateway.api("GET", "/data/api/v1/resources/singleton/ignition/security-properties")["config"]
    configuration_type = gateway.TYPE
    echo_yaml = """- route:
    id: echo
    from:
      uri: ignition-gateway-http:/echo?httpMethodRestrict=POST
      steps:
        - setHeader:
            name: Content-Type
            constant: application/json
"""

    def create(resource_type, name, config):
        gateway.api("POST", "/data/api/v1/resources/" + resource_type, [{
            "name": name, "enabled": True, "config": config,
        }])
        created.append((resource_type, name))

    def probe(label, token, method, path, expected, body=None, browser=False, csrf=False, extra=None, raw_body=None):
        # Default opener has no browser cookies and sends no CSRF header.
        headers = {"Content-Type": "application/json", **(extra or {})}
        if token is not None:
            headers["X-Ignition-API-Token"] = token
        if csrf:
            headers["X-CSRF-Token"] = gateway.CSRF
        request = urllib.request.Request(
            gateway.BASE + path, method=method, headers=headers,
            data=raw_body if raw_body is not None else None if body is None else json.dumps(body).encode())
        try:
            opener = gateway.HTTP if browser else urllib.request.build_opener()
            with opener.open(request, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        results.append({"test": label, "status": status})
        print(f"{label}: HTTP {status}")
        assert status == expected, f"{label}: expected {expected}, got {status}"
        return json.loads(raw) if status == 200 else None

    def singleton(resource_type):
        return gateway.api("GET", "/data/api/v1/resources/singleton/" + resource_type)

    def update(resource, **fields):
        gateway.api("PUT", "/data/api/v1/resources/" + resource["type"], [{
            **({"name": resource["name"]} if "name" in resource else {}),
            "signature": resource["signature"], "collection": resource["collection"], **fields,
        }])

    def create_key(label, level):
        generated = gateway.api("POST", "/data/api/v1/api-token/generate")
        name = prefix + "-" + label
        security_levels = [{"name": "Authenticated", "children": [] if level is None else [
            {"name": level, "children": []},
        ]}]
        create("ignition/api-token", name, {
            "profile": {"type": "basic-token", "secureChannelRequired": False,
                        "securityLevels": security_levels, "timestamp": int(time.time() * 1000)},
            "settings": {"tokenHash": generated["hash"]},
        })
        return name + ":" + generated["key"]

    try:
        orders, inventory = prefix + "-orders", prefix + "-inventory"
        levels_original = singleton("ignition/security-levels")
        definitions = copy.deepcopy(levels_original["config"])
        authenticated = next(x for x in definitions["securityLevels"] if x["name"] == "Authenticated")
        authenticated["children"].extend([
            {"name": orders, "children": []}, {"name": inventory, "children": []},
        ])
        update(levels_original, config=definitions)
        levels_applied = singleton("ignition/security-levels")["config"]

        orders_policy = ["Authenticated/" + orders, "Authenticated/Roles/Administrator"]
        public_path = "/v1/" + prefix + "/submit"
        configs = {
            prefix: {"yaml": echo_yaml.replace("/echo?", public_path + "?"), "httpSecurityLevels": orders_policy},
            prefix + "-other": {"yaml": echo_yaml.replace("/echo?", public_path + "-inventory?"), "httpSecurityLevels": ["Authenticated/" + inventory]},
            prefix + "-default": {"yaml": echo_yaml.replace("/echo?", public_path + "-default?")},
        }
        for name, config in configs.items():
            create(gateway.TYPE, name, config)
            gateway.wait("temporary echo route", lambda: gateway.api("GET", gateway.MODULE + "/camel-routes").get(name) == "RUNNING")

        token = create_key("orders-key", orders)
        other = create_key("inventory-key", inventory)
        authenticated_only = create_key("authenticated-key", None)
        endpoint = "/system/integrations" + public_path
        other_endpoint = endpoint + "-inventory"
        default_endpoint = endpoint + "-default"
        received = gateway.diagnostics(prefix).get("httpReceived", 0)
        probe("anonymous denied", None, "POST", endpoint, 401, {})
        probe("invalid key denied", "invalid-probe-token", "POST", endpoint, 401, {})
        probe("authenticated-only denied", authenticated_only, "POST", endpoint, 403, {})
        probe("wrong security level denied", other, "POST", endpoint, 403, {})
        probe("claimed role headers ignored", other, "POST", endpoint, 403, {}, extra={
            "X-Roles": "Administrator", "ignitionHttpActor": "admin", "CamelHttpPath": "/echo",
        })
        assert gateway.diagnostics(prefix).get("httpReceived", 0) == received, "Denied request reached Camel"
        for payload in [{"orderId": 123}, None, [1, "two"], 9007199254740993]:
            assert probe("scoped invocation without cookies/CSRF", token, "POST", endpoint, 200, payload) == payload
        probe("cross-resource invocation denied", token, "POST", other_endpoint, 403, {})
        assert probe("other resource's own key allowed", other, "POST", other_endpoint, 200, {}) == {}
        probe("missing policy denies scoped key", token, "POST", default_endpoint, 403, {})
        probe("missing policy denies administrator", None, "POST", default_endpoint, 403, {}, browser=True, csrf=True)
        probe("administrator has no implicit WRITE bypass", None, "POST", other_endpoint, 403, {}, browser=True, csrf=True)
        probe("browser missing CSRF denied", None, "POST", endpoint, 403, {}, browser=True)
        assert probe("explicit browser grant with CSRF allowed", None, "POST", endpoint, 200, {}, browser=True, csrf=True) == {}

        probe("GET denied", token, "GET", endpoint, 405)
        probe("unknown path", token, "POST", endpoint + "/missing", 404, {})
        probe("encoded request path rejected", token, "POST", endpoint.replace("/v1/", "/%76%31/"), 404, {})
        probe("malformed JSON", token, "POST", endpoint, 400, raw_body=b'{')
        probe("size limit", token, "POST", endpoint, 413, "x" * 65536)
        probe("media limit", token, "POST", endpoint, 415, {}, extra={"Content-Type": "text/plain"})
        assert probe("query cannot change target", token, "POST", endpoint + "?resource=other&endpoint=other", 200, {}) == {}
        probe("old data ingress removed", None, "POST", gateway.MODULE + "/camel-http/" + prefix + "/echo", 404, {}, browser=True, csrf=True)
        details = gateway.api("GET", gateway.MODULE + "/camel-route-details?name=" + prefix)
        assert details["httpEndpoints"] == [{"method": "POST", "path": endpoint}], details
        assert "httpPublicPaths" not in gateway.find(prefix)["config"]
        collision = prefix + "-collision"
        create(gateway.TYPE, collision, {**configs[prefix], "httpSecurityLevels": ["Authenticated/" + inventory]})
        gateway.wait("duplicate HTTP consumer rejected", lambda: gateway.api("GET", gateway.MODULE + "/camel-routes").get(collision) == "ERROR")
        assert "HTTP path already registered" in gateway.api("GET", gateway.MODULE + "/camel-route-details?name=" + collision)["startupError"]
        assert probe("duplicate cannot replace existing owner", token, "POST", endpoint, 200, {}) == {}
        probe("duplicate cannot replace permission policy", other, "POST", endpoint, 403, {})
        gateway.delete(collision)
        route = gateway.find(prefix)
        update(route, enabled=False)
        gateway.wait("HTTP consumer disabled", lambda: gateway.api("GET", gateway.MODULE + "/camel-routes").get(prefix) == "DISABLED")
        probe("disabled consumer is unregistered", token, "POST", endpoint, 404, {})
        update(gateway.find(prefix), enabled=True)
        gateway.wait("HTTP consumer reenabled", lambda: gateway.api("GET", gateway.MODULE + "/camel-routes").get(prefix) == "RUNNING")
        assert probe("HTTP consumer registers again", token, "POST", endpoint, 200, {}) == {}

        probe("configuration read denied", token, "GET", "/data/api/v1/resources/list/" + configuration_type, 403)
        # Track this name even on unexpected success so cleanup remains safe.
        config_name = prefix + "-configuration"
        created.append((configuration_type, config_name))
        probe("configuration creation denied", token, "POST", "/data/api/v1/resources/" + configuration_type, 403, [{
            "name": config_name, "enabled": True, "config": configs[prefix],
        }])
        route = gateway.find(prefix)
        probe("route permission editing denied", token, "PUT", "/data/api/v1/resources/" + gateway.TYPE, 403, [{
            "name": prefix, "signature": route["signature"], "config": route["config"],
        }])
        probe("administrative restart denied", token, "POST", gateway.MODULE + "/camel-route-restart?name=" + prefix, 403)
        if restart:
            subprocess.run(["docker", "restart", "ignition-apache-camel-acceptance-gateway-1"], check=True)
            gateway.wait("isolated Gateway login after restart", gateway.bootstrap, 180)
            gateway.wait("persisted scoped HTTP resource", lambda:
                         gateway.api("GET", gateway.MODULE + "/camel-routes").get(prefix) == "RUNNING")
            assert probe("scoped invocation survives Gateway restart", token, "POST", endpoint, 200, {}) == {}
            probe("cross-resource denial survives restart", token, "POST", other_endpoint, 403, {})
            probe("configuration denial survives restart", token, "GET", "/data/api/v1/resources/list/" + configuration_type, 403)
            probe("browser CSRF still enforced after restart", None, "POST", endpoint, 403, {}, browser=True)
        key_resource = gateway.api("GET", "/data/api/v1/resources/find/ignition/api-token/" + prefix + "-orders-key")
        update(key_resource, enabled=False)
        probe("disabled key denied immediately", token, "POST", endpoint, 401, {})
        key_resource = gateway.api("GET", "/data/api/v1/resources/find/ignition/api-token/" + prefix + "-orders-key")
        gateway.api("POST", "/data/api/v1/resources/delete/ignition/api-token?confirm=true", [{
            "name": key_resource["name"], "signature": key_resource["signature"],
        }])
        probe("deleted key denied immediately", token, "POST", endpoint, 401, {})
        replacement = create_key("replacement-key", orders)
        assert probe("replacement credential allowed", replacement, "POST", endpoint, 200, {}) == {}
        renamed = prefix + "-renamed"
        created.append((gateway.TYPE, renamed))
        gateway.api("POST", "/data/api/v1/resources/rename/" + gateway.TYPE + "/" + prefix + "?confirm=true", {"name": renamed, "references": "IGNORE"})
        gateway.wait("renamed HTTP resource", lambda: gateway.api("GET", gateway.MODULE + "/camel-routes").get(renamed) == "RUNNING")
        assert probe("consumer URI independent of resource identity", replacement, "POST", endpoint, 200, {}) == {}
        route = gateway.find(renamed)
        old_generation = gateway.diagnostics(renamed)["generation"]
        update(route, config={**route["config"], "httpSecurityLevels": []})
        gateway.wait("policy replacement", lambda: gateway.diagnostics(renamed).get("generation", 0) > old_generation)
        probe("removed grant denies previous key", replacement, "POST", endpoint, 403, {})
        probe("removed grant denies administrator", None, "POST", endpoint, 403, {}, browser=True, csrf=True)
        assert singleton("ignition/security-properties")["config"] == general_original, "Gateway general permissions changed"
    finally:
        failures = []
        for resource_type, name in reversed(created):
            try:
                try:
                    resource = gateway.api("GET", "/data/api/v1/resources/find/" + resource_type + "/" + name)
                except urllib.error.HTTPError as error:
                    if error.code == 404:
                        continue
                    raise
                gateway.api("POST", "/data/api/v1/resources/delete/" + resource_type + "?confirm=true", [{
                    "name": name, "signature": resource["signature"],
                }])
            except Exception:
                failures.append(resource_type + "/" + name)
        if levels_applied is not None:
            try:
                current = singleton("ignition/security-levels")
                if current["config"] != levels_applied:
                    raise RuntimeError("Concurrent security change; refusing to overwrite")
                update(current, config=levels_original["config"])
            except Exception:
                failures.append("ignition/security-levels (restore)")
        if failures:
            raise RuntimeError("Cleanup failed for: " + ", ".join(failures))
        print("Temporary keys/resources removed; custom level definitions restored. General permissions unchanged.")
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart", action="store_true", help="Also restart only the disposable 9288 Gateway and verify retained grants")
    main(restart=parser.parse_args().restart)
