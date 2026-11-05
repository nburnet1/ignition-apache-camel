import io
import json
import runpy
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parent
project = runpy.run_path(str(ROOT / "camel-example-project.py"))


def files(archive):
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        return {name: source.read(name) for name in source.namelist()}


class SharedExampleProjectTest(unittest.TestCase):
    def test_libraries_merge_without_losing_existing_resources_or_settings(self):
        first = project["merge_libraries"](None, {"tags": {"code.py": b"original", "resource.json": b"{}"}})
        result = project["merge_libraries"](first, {"work_orders": {"code.py": b"orders"}})
        self.assertEqual(files(first)["project.json"], files(result)["project.json"])
        self.assertEqual(b"original", files(result)["ignition/script-python/tags/code.py"])
        self.assertEqual(b"orders", files(result)["ignition/script-python/work_orders/code.py"])
        self.assertEqual("CamelExamples", json.loads(files(result)["project.json"])["title"])

    def test_conflicts_are_refused_and_identical_libraries_are_idempotent(self):
        libraries = {"tags": {"code.py": b"original", "resource.json": b"{}"}}
        archive = project["merge_libraries"](None, libraries)
        self.assertEqual(files(archive), files(project["merge_libraries"](archive, libraries)))
        with self.assertRaises(ValueError):
            project["merge_libraries"](archive, {"tags": {"code.py": b"replacement"}})

    def test_all_installers_share_one_project_and_distinct_libraries(self):
        for script in ("camel-examples.py", "camel-workorder-example.py", "camel-controlbus-example.py"):
            installer = runpy.run_path(str(ROOT / script))
            self.assertEqual("CamelExamples", installer["PROJECT"])
            self.assertIn("project=CamelExamples", installer["TOOL_YAML"])
        examples = ROOT.parent / "examples/camel"
        self.assertIn("work_orders.completeViaApi?project=CamelExamples", (examples / "work-orders.yaml").read_text())

    def test_installer_adds_only_the_requested_project_libraries(self):
        export = Mock(return_value=None)
        imported = Mock()
        globals_ = project["ensure_project"].__globals__
        with patch.dict(globals_, {"export_project": export, "import_archive": imported}):
            project["ensure_project"]({}, ("tags",))
        archive = files(imported.call_args.args[2])
        self.assertIn("ignition/script-python/tags/code.py", archive)
        self.assertNotIn("ignition/script-python/unified_readings/code.py", archive)
        self.assertNotIn("ignition/script-python/work_orders/code.py", archive)

    def test_intro_tool_endpoints_are_namespaced_by_the_fixture_api(self):
        installer = runpy.run_path(str(ROOT / "camel-examples.py"))
        acceptance = runpy.run_path(str(ROOT / "camel-acceptance.py"))
        rewritten = acceptance["fixture_http_yaml"](
            installer["TOOLS"], installer["TOOL_YAML"]
        )
        for endpoint in (
            "setup",
            "eip-snapshot",
            "eip-input",
            "unified-snapshot",
            "unified-tag",
        ):
            self.assertIn(
                "ignition-gateway-http:/"
                + installer["TOOLS"]
                + "/"
                + endpoint,
                rewritten,
            )


if __name__ == "__main__":
    unittest.main()
