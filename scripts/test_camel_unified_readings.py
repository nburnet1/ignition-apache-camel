import math
from pathlib import Path
import runpy
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "examples/camel/project/unified_readings.py"
NORMALIZE = runpy.run_path(
    str(SOURCE), init_globals={"long": int, "basestring": str, "unicode": str}
)["normalize"]


class UnifiedReadingsTest(unittest.TestCase):
    def test_http_and_mqtt_shapes_normalize_to_the_same_model(self):
        http = NORMALIZE(
            {"machine": "Press01", "temperatureC": 30},
            {"readingSource": "http"},
        )
        mqtt = NORMALIZE(
            {"device": "Press01", "temperatureF": 86},
            {"readingSource": "mqtt"},
        )

        self.assertEqual(
            {"machine": "Press01", "temperatureC": 30.0, "status": "NORMAL"},
            {key: value for key, value in http.items() if key != "source"},
        )
        self.assertEqual(
            {"machine": "Press01", "temperatureC": 30.0, "status": "NORMAL"},
            {key: value for key, value in mqtt.items() if key != "source"},
        )
        self.assertEqual(("http", "mqtt"), (http["source"], mqtt["source"]))

    def test_tag_input_uses_its_fixed_machine_and_source_annotation(self):
        self.assertEqual(
            {
                "machine": "Press03",
                "temperatureC": 45.0,
                "status": "NORMAL",
                "source": "ignition-tag",
            },
            NORMALIZE(45, {"readingSource": "ignition-tag"}),
        )

    def test_fahrenheit_conversion_precedes_high_temperature_classification(self):
        self.assertEqual(
            {
                "machine": "Press02",
                "temperatureC": 90.0,
                "status": "HIGH",
                "source": "mqtt",
            },
            NORMALIZE(
                {"device": "Press02", "temperatureF": 194},
                {"readingSource": "mqtt"},
            ),
        )

    def test_invalid_source_shapes_and_temperatures_are_rejected(self):
        cases = [
            ({}, {"readingSource": "http"}),
            ({"machine": "Press01", "temperatureC": "30"}, {"readingSource": "http"}),
            ({"device": "Press02", "temperatureF": math.nan}, {"readingSource": "mqtt"}),
            (151, {"readingSource": "ignition-tag"}),
            (30, {"readingSource": "unknown"}),
        ]
        for payload, headers in cases:
            with self.subTest(payload=payload, headers=headers), self.assertRaises(Exception):
                NORMALIZE(payload, headers)


if __name__ == "__main__":
    unittest.main()
