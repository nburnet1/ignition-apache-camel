"""Normalize different source payloads into one machine-reading model."""
import math

ROOT = "[default]CamelExamples/Unified"


def _ensure(parent, definition):
    path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
    if not system.tag.exists(path):
        qualities = system.tag.configure(parent, [definition], "a")
        if not all(quality.isGood() for quality in qualities):
            raise Exception("Could not create unified-reading example tag: " + path)
    return path


def setup(payload, headers):
    parent = _ensure("[default]", {"name": "CamelExamples", "tagType": "Folder"})
    _ensure(parent, {"name": "Unified", "tagType": "Folder"})
    _ensure(ROOT, {"name": "RawTemperature", "tagType": "AtomicTag",
        "valueSource": "memory", "dataType": "Float8", "value": 20.0})
    _ensure(ROOT, {"name": "Latest", "tagType": "AtomicTag",
        "valueSource": "memory", "dataType": "String",
        "value": "Waiting for a reading"})
    return "ready"


def normalize(payload, headers):
    source = unicode(headers.get("readingSource", ""))
    if source == "http":
        if not isinstance(payload, dict):
            raise Exception("HTTP reading must be a JSON object")
        machine = payload.get("machine")
        value = payload.get("temperatureC")
        unit = "C"
    elif source == "mqtt":
        if not isinstance(payload, dict):
            raise Exception("MQTT reading must be a JSON object")
        machine = payload.get("device")
        value = payload.get("temperatureF")
        unit = "F"
    elif source == "ignition-tag":
        machine = "Press03"
        value = payload
        unit = "C"
    else:
        raise Exception("Unknown reading source")
    if not isinstance(machine, basestring) or not machine.strip() or len(machine) > 80:
        raise Exception("Machine must be a non-empty name up to 80 characters")
    if isinstance(value, bool) or not isinstance(value, (int, long, float)):
        raise Exception("Temperature must be numeric")
    numeric = float(value)
    if math.isnan(numeric) or math.isinf(numeric):
        raise Exception("Temperature must be finite")
    celsius = (numeric - 32.0) * 5.0 / 9.0 if unit == "F" else numeric
    if not -50 <= celsius <= 150:
        raise Exception("Temperature must be between -50 and 150 Celsius")
    return {"machine": unicode(machine), "temperatureC": round(celsius, 2),
        "status": "HIGH" if celsius >= 80 else "NORMAL", "source": source}


def snapshot(payload, headers):
    names = ["RawTemperature", "Latest"]
    values = system.tag.readBlocking([ROOT + "/" + name for name in names])
    if not all(value.quality.isGood() for value in values):
        raise Exception("Unified-reading example tag quality is bad")
    return dict((name, {"value": value.value, "quality": str(value.quality)})
        for name, value in zip(names, values))


def writeTagInput(payload, headers):
    value = payload.get("value") if isinstance(payload, dict) else None
    if isinstance(value, bool) or not isinstance(value, (int, long, float)):
        raise Exception("Submit a numeric value")
    qualities = system.tag.writeBlocking([ROOT + "/RawTemperature"], [value])
    if not qualities[0].isGood():
        raise Exception("Could not write the unified-reading input tag")
    return "written"
