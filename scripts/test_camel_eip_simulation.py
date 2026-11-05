"""Offline contracts; real protocol behavior is checked on isolated Gateway 9288."""
import ast
import io
import json
import runpy
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(ROOT / "scripts/camel-eip-simulation.py"))


class SimulationContracts(unittest.TestCase):
    def test_exactly_64_named_patterns_have_real_assertion_methods(self):
        patterns = json.loads((m["ASSETS"] / "patterns.json").read_text())
        self.assertEqual(64, len(patterns))
        self.assertEqual(64, len({row["name"] for row in patterns}))
        tree = ast.parse((m["ASSETS"] / "eip_patterns.py").read_text())
        methods = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)}
        for row in patterns:
            method = methods["test_" + row["scenario"].replace("-", "_")]
            self.assertTrue(any(isinstance(node, (ast.Assert, ast.Raise)) for node in ast.walk(method)), row["name"])

    def test_archive_is_one_runnable_global_project_with_native_handlers(self):
        data = m["archive"]("global", ["eip_simulation", "eip_routes", "eip_patterns"])
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            config = json.loads(z.read("project.json"))
            self.assertTrue(config["enabled"])
            self.assertFalse(config["inheritable"])
            self.assertEqual("", config["parent"])
            for name in ["startWorkOrder", "completeWorkOrder", "patternService"]:
                self.assertIn("ignition/message/" + name + "/handleMessage.py", z.namelist())
            source = z.read("ignition/script-python/eip_manifest/code.py").decode()
            namespace = {}
            exec(source, namespace)
            self.assertEqual(64, len(namespace["PATTERNS"]))

    def test_perspective_saved_scripts_have_indented_function_bodies(self):
        view = m["view"]()
        for component in view["root"]["children"]:
            for prop in component.get("propConfig", {}).values():
                for transform in prop["binding"].get("transforms", []):
                    code = transform["code"]
                    self.assertTrue(code.startswith("\t"))
                    ast.parse("def transform(self, value, quality, timestamp):\n" + code)
            for action in component.get("events", {}).get("component", {}).values():
                self.assertTrue(action["config"]["script"].startswith("\t"))
                ast.parse("def runAction(self, event):\n" + action["config"]["script"])

    def test_all_native_components_are_used_and_mqtt_cannot_feed_itself(self):
        source = (m["ASSETS"] / "eip_routes.py").read_text()
        for scheme in ["ignition-tag:", "ignition-script:", "ignition-message:", "ignition-perspective-message:", "ignition-gateway-http:"]:
            self.assertIn(scheme, source)
        self.assertIn("CamelPahoOverrideTopic", source)
        self.assertIn("ignition-tag:[default]**?includeInitial=true", source)
        self.assertNotIn('from_("simulation-mqtt:', source)


if __name__ == "__main__":
    unittest.main()
