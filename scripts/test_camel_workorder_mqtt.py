"""SDK-free serialization, type preservation and mirror-path safety checks."""
import json
import runpy
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class Lock:
    def __init__(self):
        self.lock_ = threading.RLock()
    def lock(self):
        self.lock_.acquire()
    def unlock(self):
        self.lock_.release()


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.configs = {}
        good = types.SimpleNamespace(isGood=lambda: True)
        def configure(parent, definitions, policy):
            for definition in definitions:
                path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
                self.assertNotIn(path, self.configs)
                self.configs[path] = definition
            return [good] * len(definitions)
        tag = types.SimpleNamespace(exists=lambda path: path in self.configs,
            getConfiguration=lambda path, recursive: [self.configs[path]], configure=configure)
        modules = {name: types.ModuleType(name) for name in ("java", "java.util", "java.util.concurrent", "java.util.concurrent.locks")}
        modules["java.util.concurrent.locks"].ReentrantLock = Lock
        with patch.dict("sys.modules", modules):
            self.code = runpy.run_path(str(Path(__file__).resolve().parents[1] / "examples/camel/project/work_order_mqtt.py"),
                init_globals={"system": types.SimpleNamespace(tag=tag, util=types.SimpleNamespace(jsonEncode=json.dumps, jsonDecode=json.loads)),
                              "unicode": str, "unichr": chr})

    def test_scalar_values_and_datatypes_survive_the_frame_and_mirror_setup(self):
        for name, datatype, value in (("Count", "Int4", 12345), ("Flag", "Boolean", False), ("Order", "String", '{"quantity":2}')):
            source = self.code["SOURCE"] + "/Probe/" + name
            self.configs[source] = {"tagType": "AtomicTag", "dataType": datatype}
            encoded = self.code["encodeTag"](value, {"ignitionTagPath": source, "ignitionTagQuality": "Good", "ignitionTagTimestamp": 123})
            result = self.code["prepareMirror"](encoded["message"], {"CamelMqttTopic": encoded["topic"]})
            self.assertEqual(self.code["MIRROR"] + "/Probe/" + name, result["tagPath"])
            self.assertEqual(value, result["value"])
            self.assertEqual(datatype, self.configs[result["tagPath"]]["dataType"])

    def test_topic_encoding_preserves_case_underscores_and_unicode(self):
        for name in ("ActiveWorkOrder", "activeworkorder", "_value", "press-01", "caf\u00e9", "Press 01"):
            self.assertEqual(name, self.code["_unsegment"](self.code["_segment"](name)))
        self.assertNotEqual(self.code["_segment"]("Status"), self.code["_segment"]("status"))

    def test_foreign_topics_traversal_properties_and_unsupported_types_are_rejected(self):
        frame = json.dumps({"version": 1, "dataType": "String", "value": "x"})
        for topic in ("foreign/x", self.code["TOPIC"] + "_2E_2E/leaf", self.code["TOPIC"] + "a_2Fb",
                      self.code["TOPIC"] + "x_2EQuality", self.code["TOPIC"] + "", self.code["TOPIC"] + "x//y",
                      self.code["TOPIC"] + "_252E_252E", self.code["TOPIC"] + "a_26capacity_3D1", self.code["TOPIC"] + "evil_0A"):
            with self.assertRaises(Exception):
                self.code["prepareMirror"](frame, {"CamelMqttTopic": topic})
        with self.assertRaises(Exception):
            self.code["prepareMirror"](json.dumps({"version": 1, "dataType": "Document", "value": {}}), {"CamelMqttTopic": self.code["TOPIC"] + "x"})
        with self.assertRaises(Exception):
            self.code["encodeTag"]("x", {"ignitionTagPath": self.code["MIRROR"] + "/x"})

    def test_existing_mirror_datatype_is_not_silently_overwritten(self):
        path = self.code["MIRROR"] + "/x"
        self.configs[path] = {"tagType": "AtomicTag", "dataType": "Boolean"}
        with self.assertRaises(Exception):
            self.code["prepareMirror"](json.dumps({"version": 1, "dataType": "String", "value": "x"}), {"CamelMqttTopic": self.code["TOPIC"] + "x"})
        self.assertEqual("Boolean", self.configs[path]["dataType"])


if __name__ == "__main__":
    unittest.main()
