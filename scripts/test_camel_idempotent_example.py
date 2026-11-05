import json
from pathlib import Path
import runpy
import types
import unittest
from unittest.mock import patch


class IdempotentHelpersTest(unittest.TestCase):
    def setUp(self):
        self.values = {}
        good = types.SimpleNamespace(isGood=lambda: True)

        def configure(parent, definitions, policy):
            for definition in definitions:
                path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
                self.assertNotIn(path, self.values)
                self.values[path] = definition.get("value")
            return [good] * len(definitions)

        def write(paths, values):
            for path, value in zip(paths, values):
                self.assertIn(path, self.values)
                self.values[path] = value
            return [good] * len(paths)

        def read(paths):
            return [
                types.SimpleNamespace(value=self.values[path], quality=good)
                for path in paths
            ]

        system = types.SimpleNamespace(
            tag=types.SimpleNamespace(
                exists=lambda path: path in self.values,
                configure=configure,
                writeBlocking=write,
                readBlocking=read,
            ),
            util=types.SimpleNamespace(jsonEncode=json.dumps),
        )
        modules = {
            name: types.ModuleType(name)
            for name in (
                "org",
                "org.apache",
                "org.apache.camel",
                "org.apache.camel.support",
                "org.apache.camel.support.processor",
                "org.apache.camel.support.processor.idempotent",
            )
        }
        repository_module = modules[
            "org.apache.camel.support.processor.idempotent"
        ]
        setattr(repository_module, "MemoryIdempotentRepository", types.SimpleNamespace(
            memoryIdempotentRepository=lambda capacity: capacity
        ))
        with patch.dict("sys.modules", modules):
            self.code = runpy.run_path(
                str(
                    Path(__file__).resolve().parents[1]
                    / "examples/camel/project/idempotent_consumer.py"
                ),
                init_globals={"system": system, "basestring": str},
            )
        self.code["setup"]({}, {})

    def test_validation_requires_a_stable_id_and_accepts_any_json_value(self):
        for value in (None, 12, "text", [1, 2], {"quantity": 3}):
            payload = {"id": "MSG-1", "value": value}
            self.assertIs(payload, self.code["validate"](payload, {}))
        for payload in (
            {"value": 1},
            {"id": "", "value": 1},
            {"id": 1, "value": 1},
            {"id": "a\n", "value": 1},
            {"id": "MSG-1"},
            [],
        ):
            with self.assertRaises(Exception):
                self.code["validate"](payload, {})

    def test_setup_is_idempotent_and_exposes_only_the_behavior_tags(self):
        self.assertEqual(
            {
                "[default]CamelExamples/IdempotentConsumer/Input",
                "[default]CamelExamples/IdempotentConsumer/LastDelivered",
                "[default]CamelExamples/IdempotentConsumer/LastResult",
            },
            {path for path in self.values if "/IdempotentConsumer/" in path},
        )
        before = self.values.copy()
        self.code["setup"]({}, {})
        self.assertEqual(before, self.values)

    def test_write_input_validates_and_serializes_the_command(self):
        payload = {"id": "MSG-2", "value": {"quantity": 5}}
        self.assertEqual("written", self.code["writeInput"](payload, {}))
        self.assertEqual(
            payload,
            json.loads(
                self.values["[default]CamelExamples/IdempotentConsumer/Input"]
            ),
        )

    def test_repository_capacity_is_explicit_and_bounded(self):
        self.assertEqual(1000, self.code["repositoryFactory"]({}))
        self.assertEqual(5, self.code["repositoryFactory"]({"capacity": "5"}))
        for value in ("0", "100001", "invalid"):
            with self.assertRaises(Exception):
                self.code["repositoryFactory"]({"capacity": value})


if __name__ == "__main__":
    unittest.main()
