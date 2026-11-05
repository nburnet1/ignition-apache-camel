"""Project archive/import protocol and restart provisioning regression tests."""
import io
import json
import runpy
import unittest
import urllib.parse
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "docker/camel-project"


class ProjectProtocol:
    """In-memory native import boundary; stores the bytes submitted by the client."""

    def __init__(self):
        self.projects = {}
        self.success = True

    def open(self, request, timeout):
        if request.get_method() != "POST":
            raise AssertionError("Expected native project import")
        url = urllib.parse.urlsplit(request.full_url)
        if (url.netloc != "localhost:9288"
                or not url.path.startswith("/data/api/v1/projects/import/")
                or url.query != "overwrite=true"):
            raise AssertionError("Unexpected native project import URL: " + request.full_url)
        if request.get_header("X-csrf-token") != "fixture-csrf":
            raise AssertionError("Native import requires authenticated session CSRF")
        if request.get_header("Content-type") != "application/zip":
            raise AssertionError("Native import requires a ZIP archive")
        with zipfile.ZipFile(io.BytesIO(request.data)) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        if self.success:
            self.projects[url.path.rsplit("/", 1)[1]] = entries
        return io.BytesIO(json.dumps({"success": self.success}).encode())


class BeforeRouteSetup(Exception):
    """Stop full acceptance after project provisioning, before route mutations."""


def stop_before_routes(_):
    raise BeforeRouteSetup()


class AcceptanceProjectTest(unittest.TestCase):
    def setUp(self):
        self.module = runpy.run_path(str(ROOT / "scripts/camel-acceptance.py"))
        self.globals = self.module["main"].__globals__
        self.http = ProjectProtocol()
        self.context = patch.dict(self.globals, {"HTTP": self.http, "CSRF": "fixture-csrf"})
        self.context.start()
        self.addCleanup(self.context.stop)

    def test_fixture_archive_import_preserves_every_file_at_project_root(self):
        self.module["import_project"]("camel_acceptance", FIXTURE)
        expected = {
            path.relative_to(FIXTURE).as_posix(): path.read_bytes()
            for path in FIXTURE.rglob("*") if path.is_file()
        }
        self.assertEqual(expected, self.http.projects["camel_acceptance"])

    def test_script_import_preserves_reload_project_contract(self):
        self.module["import_project"]("camel_reload_parent", "def orders(builder): pass\n",
                                      parent="fixture_parent", inheritable=True)
        project = self.http.projects["camel_reload_parent"]
        self.assertEqual(b"def orders(builder): pass\n",
                         project["ignition/script-python/routes/code.py"])
        manifest = json.loads(project["project.json"])
        self.assertEqual(("fixture_parent", True), (manifest["parent"], manifest["inheritable"]))
        self.assertEqual(["code.py"], json.loads(
            project["ignition/script-python/routes/resource.json"])["files"])

    def test_native_import_rejection_is_reported(self):
        self.http.success = False
        with self.assertRaises(AssertionError):
            self.module["import_project"]("camel_acceptance", FIXTURE)
        self.assertEqual({}, self.http.projects)

    def api(self, method, path, body=None):
        if path == "/data/app/navigation":
            return {"Apache Camel": "/services/apache-camel/integrations"}
        if path == "/data/api/v1/entity/section/Services":
            return [{
                "name": "Apache Camel", "navigation": {"tags": ["section-overview"]},
                "diagnostics": {"metrics": {}, "healthchecks": {
                    "status": {"result": {"healthy": True}}}},
            }]
        if path == "/data/apache-camel/camel-routes":
            return {
                "camel-acceptance-transfer": "RUNNING",
                "camel-acceptance-http": "RUNNING",
            }
        if method == "POST" and path == "/system/integrations/camel-acceptance-http/number":
            assert body is not None
            return {"number": body["number"] + 1, "writeVerified": True}
        raise AssertionError("Unexpected acceptance API call: " + path)

    def test_normal_acceptance_imports_fixture_before_creating_routes(self):
        with patch.dict(self.globals, {
            "bootstrap": lambda: True, "api": self.api,
            "delete": stop_before_routes,
        }), patch("sys.argv", ["camel-acceptance.py"]):
            with self.assertRaises(BeforeRouteSetup):
                self.module["main"]()
        self.assertEqual((FIXTURE / "project.json").read_bytes(),
                         self.http.projects["camel_acceptance"]["project.json"])
        self.assertIn("ignition/script-python/camel_routes/code.py",
                      self.http.projects["camel_acceptance"])

    def test_restart_verification_preserves_existing_project_bytes(self):
        original = {"project.json": b'{"title":"existing settings"}',
                    "ignition/script-python/camel_fixture/code.py": b"# existing script"}
        self.http.projects["camel_acceptance"] = original.copy()
        with patch.dict(self.globals, {
            "bootstrap": lambda: True, "api": self.api,
            "healthy": lambda *_: True, "verify_mqtt5": lambda **_: None,
        }), patch("sys.argv", ["camel-acceptance.py", "--verify-restart"]):
            self.module["main"]()
        self.assertEqual(original, self.http.projects["camel_acceptance"])


if __name__ == "__main__":
    unittest.main()
