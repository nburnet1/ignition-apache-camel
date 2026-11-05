"""Kamelet temperature example: pure normalization and allowlisted demo controls."""
import math

ROOT = "[default]CamelExamples/Kamelets"
INPUTS = {"C": "CelsiusInput", "F": "FahrenheitInput"}


def normalize(payload, headers):
    if isinstance(payload, bool) or not isinstance(payload, (int, long, float)):
        raise ValueError("Temperature must be numeric")
    value = float(payload)
    if math.isnan(value) or math.isinf(value):
        raise ValueError("Temperature must be finite")
    unit = headers["kameletInputUnit"]
    if unit not in INPUTS:
        raise ValueError("Unit must be C or F")
    celsius = (value - 32.0) * 5.0 / 9.0 if unit == "F" else value
    if not -50 <= celsius <= 150:
        raise ValueError("Temperature must be between -50 and 150 Celsius")
    warning = float(headers["kameletWarningC"])
    if math.isnan(warning) or math.isinf(warning) or not -50 <= warning <= 150:
        raise ValueError("Warning threshold must be between -50 and 150 Celsius")
    machine = headers["kameletMachine"]
    if not isinstance(machine, basestring) or not machine.strip() or len(machine) > 80:
        raise ValueError("Machine must be a non-empty name up to 80 characters")
    return {"machine": machine, "temperatureC": round(celsius, 2),
            "status": "HIGH" if celsius >= warning else "NORMAL"}


def requireGood(results):
    if not all(result.isGood() for result in results):
        raise ValueError("Demo tag operation failed")


def setup(payload, headers):
    # Create only missing demo folders/tags; never reset existing values.
    for parent, name in (("[default]", "CamelExamples"),
                         ("[default]CamelExamples", "Kamelets")):
        if not system.tag.exists(parent + "/" + name):
            requireGood(system.tag.configure(parent, [{"name": name, "tagType": "Folder"}], "a"))
    for name, dataType, value in (("CelsiusInput", "Float8", 20.0),
                                  ("FahrenheitInput", "Float8", 68.0),
                                  ("LatestReading", "String", "Waiting for a reading")):
        path = ROOT + "/" + name
        if not system.tag.exists(path):
            requireGood(system.tag.configure(ROOT, [{"name": name, "tagType": "AtomicTag",
                "valueSource": "memory", "dataType": dataType, "value": value}], "a"))
        else:
            config = system.tag.getConfiguration(path, False)[0]
            if str(config.get("dataType")) != dataType or str(config.get("valueSource")) != "memory":
                raise ValueError("Existing demo tag has incompatible configuration: " + name)
    return inspect(payload, headers)


def inspect(payload, headers):
    names = ["CelsiusInput", "FahrenheitInput", "LatestReading"]
    readings = system.tag.readBlocking([ROOT + "/" + name for name in names], 5000)
    return dict((name, {"value": reading.value, "good": reading.quality.isGood()})
                for name, reading in zip(names, readings))


def input(payload, headers):
    unit = payload.get("unit")
    if unit not in INPUTS:
        raise ValueError("Unit must be C or F")
    value = payload.get("value")
    if isinstance(value, bool) or not isinstance(value, (int, long, float)):
        raise ValueError("Value must be numeric")
    requireGood(system.tag.writeBlocking([ROOT + "/" + INPUTS[unit]], [value], 5000))
    # Tag source delivery is asynchronous. Query status separately to observe it.
    return {"submitted": True}
