import io
import runpy
import unittest
from pathlib import Path
from unittest.mock import patch


class ConfigurationFixtureTest(unittest.TestCase):
    def setUp(self):
        self.module = runpy.run_path(str(Path(__file__).with_name("camel-configuration-acceptance.py")))
        self.globals = self.module["fixtures"].__globals__

    def test_native_secret_provider_and_database_are_provisioned_without_runtime_profiles(self):
        calls = []
        encryptions = []

        def api(method, path, body=None):
            calls.append((method, path, body))
            if path == "/data/app/session":
                return {"csrfToken": "test-csrf"}

        class Http:
            def open(self, request, timeout):
                encryptions.append(request)
                return io.BytesIO(b'"test-ciphertext"')

        with patch.dict(self.globals, {"find": lambda *_: None, "api": api,
                                      "m": {"BASE": "http://localhost:9288", "HTTP": Http()}}):
            self.module["fixtures"]()
        self.assertEqual(2, len(encryptions))
        for request in encryptions:
            self.assertEqual("test-csrf", request.get_header("X-csrf-token"))
            self.assertTrue(request.full_url.endswith("/data/api/v1/encryption/encrypt"))
        resources = {path: body for method, path, body in calls if method == "POST"}
        self.assertEqual({"/data/api/v1/resources/ignition/secret-provider",
                          "/data/api/v1/resources/ignition/database-connection"}, set(resources))
        provider = resources["/data/api/v1/resources/ignition/secret-provider"][0]
        self.assertEqual("camel-configuration-secrets", provider["name"])
        self.assertEqual({"credential", "database"}, set(provider["config"]["settings"]["secrets"]))
        database = resources["/data/api/v1/resources/ignition/database-connection"][0]
        self.assertEqual("CamelAcceptance", database["name"])
        self.assertEqual({"type": "Referenced", "data": {"providerName": provider["name"], "secretName": "database"}}, database["config"]["password"])
        self.assertNotIn("configuration-secret-proof", str(resources))

    def test_existing_native_fixtures_are_not_overwritten(self):
        def forbidden(*_):
            raise AssertionError("Existing fixtures must not be overwritten")
        with patch.dict(self.globals, {"find": lambda *_: {"name": "existing"}, "api": forbidden}):
            self.module["fixtures"]()

    def test_quartz_delivery_uses_runtime_counters_not_database_tables(self):
        calls = []
        def api(method, path, body=None):
            calls.append((method, path))
            return {"routes": [{"id": "unrelated", "completed": 100}, {"id": "sections-clock", "completed": 3}]}
        with patch.dict(self.globals, {"api": api}):
            self.assertEqual(3, self.module["fired"]())
        self.assertEqual([("GET", "/data/apache-camel/camel-route-details?name=camel-acceptance-sections")], calls)


if __name__ == "__main__":
    unittest.main()
