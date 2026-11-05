import ast
import io
import json
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
m = runpy.run_path(str(ROOT / "scripts/camel-component-benchmark.py"))


class ComponentBenchmarkContracts(unittest.TestCase):
    def test_fixture_is_additive_and_keeps_one_project(self):
        resources = m["resources"]()
        self.assertNotIn("project.json", resources)
        self.assertNotIn(m["PAGE_CONFIG"], resources)
        self.assertTrue(all(name.startswith(("ignition/script-python/component_benchmark/", "ignition/message/camelComponentBenchmark", "com.inductiveautomation.perspective/views/ComponentBenchmark/")) for name in resources))
        self.assertEqual(resources, m["files"](m["pack"](resources)))

    def test_native_endpoints_and_real_correlated_receipts_are_present(self):
        source = (ROOT / "benchmarks/ignition-components/project/component_benchmark.py").read_text()
        ast.parse(source)
        for scheme in ["ignition-tag:", "ignition-script:", "ignition-message:", "ignition-perspective-message:", "ignition-gateway-http:"]:
            self.assertIn(scheme, source)
        self.assertIn("signal.received - started", source)
        self.assertIn('getattr(signal.latch, "await")', source)
        self.assertNotIn("Stub", source)

    def test_perspective_handler_uses_the_native_component_receipt_path(self):
        resources = m["resources"]()
        view = json.loads(resources["com.inductiveautomation.perspective/views/ComponentBenchmark/view.json"])
        script = view["root"]["children"][1]["scripts"]["messageHandlers"][0]["script"]
        ast.parse("def onMessage(self, payload):\n" + script)
        self.assertIn("direct:perspective-delivered", script)

    def test_summary_includes_tail_latency_samples_and_errors(self):
        result = m["summary"]("test", 128, [1000, 2000, 3000, 4000], 1000000)
        self.assertEqual(2, result["p50Micros"])
        self.assertEqual(4, result["p99Micros"])
        self.assertEqual(0, result["errors"])
        self.assertEqual(4, result["samples"])


if __name__ == "__main__":
    unittest.main()
