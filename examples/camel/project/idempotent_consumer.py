"""Helpers for the bounded Idempotent Consumer example."""
from org.apache.camel.support.processor.idempotent import MemoryIdempotentRepository
import re

ROOT = "[default]CamelExamples/IdempotentConsumer"


def repositoryFactory(config):
    """One bounded, volatile repository per referencing Camel route resource."""
    capacity = int(config.get("capacity", "1000"))
    if not 1 <= capacity <= 100000:
        raise Exception("Use an idempotent repository capacity from 1 to 100000")
    return MemoryIdempotentRepository.memoryIdempotentRepository(capacity)


def _ensure(parent, definition):
    path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
    if not system.tag.exists(path):
        qualities = system.tag.configure(parent, [definition], "a")
        if not all(quality.isGood() for quality in qualities):
            raise Exception("Could not create Idempotent Consumer example tags")
    return path


def setup(payload, headers):
    parent = _ensure("[default]", {"name": "CamelExamples", "tagType": "Folder"})
    _ensure(parent, {"name": "IdempotentConsumer", "tagType": "Folder"})
    for name in ["Input", "LastDelivered", "LastResult"]:
        _ensure(ROOT, {"name": name, "tagType": "AtomicTag", "valueSource": "memory",
            "dataType": "String", "value": ""})
    return "ready"


def _read(names):
    values = system.tag.readBlocking([ROOT + "/" + name for name in names])
    if not all(value.quality.isGood() for value in values):
        raise Exception("Idempotent Consumer example tag quality is bad")
    return [value.value for value in values]


def _write(values):
    names = sorted(values)
    qualities = system.tag.writeBlocking([ROOT + "/" + name for name in names], [values[name] for name in names])
    if not all(quality.isGood() for quality in qualities):
        raise Exception("Idempotent Consumer example tag write failed")


def validate(payload, headers):
    if not isinstance(payload, dict) or "value" not in payload:
        raise Exception("Submit one JSON object with id and value")
    identifier = payload.get("id")
    if not isinstance(identifier, basestring) or not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z", identifier):
        raise Exception("id must be 1-64 letters, digits, dots, underscores or hyphens, starting with a letter or digit")
    return payload


def snapshot(payload, headers):
    names = ["Input", "LastDelivered", "LastResult"]
    return dict(zip(names, _read(names)))


def writeInput(payload, headers):
    validate(payload, headers)
    _write({"Input": system.util.jsonEncode(payload)})
    return "written"
