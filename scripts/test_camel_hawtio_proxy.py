import base64
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location("hawtio_proxy", Path(__file__).with_name("camel-hawtio-proxy.py"))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class Client:
    MODULE = "/data/apache-camel"

    def __init__(self):
        self.calls = []

    def api(self, *args):
        self.calls.append(args)
        return {"status": 200, "value": "test"}


class HawtioProxyTest(unittest.TestCase):
    def setUp(self):
        self.client = Client()
        self.server = proxy.make_server(self.client, "test-password-only", port=0)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.url = "http://127.0.0.1:%s" % self.server.server_port
        self.auth = "Basic " + base64.b64encode(b"hawtio:test-password-only").decode()

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def request(self, path="/jolokia/", body=None, **headers):
        defaults = {"Authorization": self.auth, "Content-Type": "application/json"}
        defaults.update(headers)
        request = urllib.request.Request(self.url + path, data=None if body is None else json.dumps(body).encode(), headers=defaults)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_forwards_only_fixed_management_destination(self):
        self.assertEqual("127.0.0.1", self.server.server_address[0])
        self.assertEqual(200, self.request(body={"type": "version"})[0])
        self.assertEqual([("POST", self.client.MODULE + "/camel-jolokia", {"type": "version"})], self.client.calls)
        self.assertEqual(404, self.request(path="/data/app/session")[0])
        self.assertEqual(1, len(self.client.calls))

    def test_denies_unauthenticated_and_cross_origin_calls(self):
        self.assertEqual(401, self.request(Authorization="")[0])
        self.assertEqual(401, self.request(Authorization="Basic bad")[0])
        self.assertEqual(403, self.request(Origin="http://untrusted.invalid")[0])
        self.assertEqual([], self.client.calls)

    def test_version_probe_never_forwards_get_operations(self):
        self.assertEqual(200, self.request(path="/jolokia/?type=exec&operation=stop")[0])
        self.assertEqual([("GET", self.client.MODULE + "/camel-jolokia/version")], self.client.calls)

    def test_allows_only_configured_forwarded_console_origin(self):
        self.assertEqual(200, self.request(body={"type": "version"}, Origin="http://127.0.0.1:9390")[0])
        self.assertEqual(403, self.request(body={"type": "version"}, Origin="http://127.0.0.1:9999")[0])
        self.assertEqual(1, len(self.client.calls))

    def test_rejects_non_json_body_and_oversized_body(self):
        self.assertEqual(415, self.request(body={}, **{"Content-Type": "text/plain"})[0])
        self.assertEqual(413, self.request(body="x" * 65537)[0])
        self.assertEqual([], self.client.calls)


    def test_container_listener_requires_explicit_bind_override(self):
        with proxy.make_server(self.client, "password", port=0, bind_host="0.0.0.0",
                               console_origin="http://127.0.0.1:9291") as server:
            self.assertEqual("0.0.0.0", server.server_address[0])

    def test_renews_only_unauthenticated_native_sessions_once(self):
        client = Mock(MODULE=Client.MODULE)
        client.api.side_effect = [urllib.error.HTTPError("http://test", 401, "expired", {}, None), {"status": 200}]
        client.bootstrap.return_value = True
        self.assertEqual({"status": 200}, proxy.RenewableSession(client).api("GET", "/version"))
        client.bootstrap.assert_called_once()
        self.assertEqual(2, client.api.call_count)
        client.reset_mock()
        client.api.side_effect = urllib.error.HTTPError("http://test", 403, "denied", {}, None)
        with self.assertRaises(urllib.error.HTTPError):
            proxy.RenewableSession(client).api("POST", "/operation", {})
        client.bootstrap.assert_not_called()
        self.assertEqual(1, client.api.call_count)


if __name__ == "__main__":
    unittest.main()
