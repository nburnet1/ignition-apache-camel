"""Pure normalization tests; no Gateway or extension activation required."""
import math
import io
import json
from pathlib import Path
import runpy
import unittest
import urllib.error
from unittest.mock import Mock, patch
import zipfile


SOURCE = Path(__file__).resolve().parents[1] / "examples/camel/project/kamelets.py"
NORMALIZE = runpy.run_path(str(SOURCE), init_globals={"long": int, "basestring": str})["normalize"]
INSTALLER = runpy.run_path(str(Path(__file__).with_name("camel-kamelet-example.py")))


class KameletNormalizationTest(unittest.TestCase):
    def normalize(self, value, unit="C", **overrides):
        headers = {"kameletInputUnit": unit, "kameletMachine": "Press01", "kameletWarningC": "80"}
        headers.update(overrides)
        return NORMALIZE(value, headers)

    def test_two_units_become_the_same_reading(self):
        self.assertEqual(self.normalize(30), self.normalize(86, "F"))
        self.assertEqual({"machine": "Press01", "temperatureC": 30.0, "status": "NORMAL"}, self.normalize(30))

    def test_threshold_is_in_celsius_and_is_configurable(self):
        self.assertEqual("HIGH", self.normalize(176, "F")["status"])
        self.assertEqual("NORMAL", self.normalize(79.9)["status"])
        self.assertEqual("HIGH", self.normalize(30, kameletWarningC="25")["status"])

    def test_invalid_readings_fail_before_output(self):
        for value in (True, "30", None, {}, math.nan, math.inf, -51, 151):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.normalize(value)
        with self.assertRaises(ValueError):
            self.normalize(30, "K")
        for threshold in ("nan", "inf", "151"):
            with self.subTest(threshold=threshold), self.assertRaises(ValueError):
                self.normalize(30, kameletWarningC=threshold)

    def test_instance_parameters_do_not_leak(self):
        self.assertEqual("Press02", self.normalize(86, "F", kameletMachine="Press02")["machine"])
        self.assertEqual("Press01", self.normalize(30)["machine"])


class KameletInstallerTest(unittest.TestCase):
    def project_archive(self, code):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("ignition/script-python/kamelets/code.py", code)
            # Gateway exports reformat JSON and may add native metadata.
            archive.writestr("ignition/script-python/kamelets/resource.json", json.dumps({
                "scope": "A", "files": ["code.py"], "attributes": {"hintScope": 1, "native": "kept"}
            }, indent=2))
        return output.getvalue()

    def test_existing_identical_code_is_preserved_despite_reformatted_metadata(self):
        helper = {"export_project": Mock(return_value=self.project_archive(SOURCE.read_bytes())),
                  "import_archive": Mock(), "merge_libraries": Mock()}
        with patch("runpy.run_path", return_value=helper):
            INSTALLER["ensure_project"]({})
        helper["import_archive"].assert_not_called()
        helper["merge_libraries"].assert_not_called()

    def test_existing_changed_code_is_never_overwritten(self):
        helper = {"export_project": Mock(return_value=self.project_archive(b"# user code")),
                  "import_archive": Mock(), "merge_libraries": Mock()}
        with patch("runpy.run_path", return_value=helper), self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            INSTALLER["ensure_project"]({})
        helper["import_archive"].assert_not_called()

    def test_definitions_are_created_as_native_resources_not_uploaded_jars(self):
        api = Mock(side_effect=[urllib.error.HTTPError("url", 404, "missing", {}, None), None])
        config = {"language": "YAML", "yaml": "kind: Kamelet", "kamelets": []}
        INSTALLER["ensure_definition"]({"api": api}, "test-action", config)
        method, path, body = api.call_args.args
        self.assertEqual("POST", method)
        self.assertEqual("/data/api/v1/resources/" + INSTALLER["KAMELET_TYPE"], path)
        self.assertEqual(config, body[0]["config"])


if __name__ == "__main__":
    unittest.main()
