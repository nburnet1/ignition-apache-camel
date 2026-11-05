"""Setup and inspection helpers for the YAML EIP example."""

_ROOT = "[default]CamelExamples"


def _ensure(parent, definition):
    path = parent + ("" if parent.endswith("]") else "/") + definition["name"]
    if not system.tag.exists(path):
        result = system.tag.configure(parent, [definition], "a")
        if not all(quality.isGood() for quality in result):
            raise Exception("Could not create example tag: " + path)
    return path


def _folders(parts):
    parent = "[default]"
    for part in parts:
        parent = _ensure(parent, {"name": part, "tagType": "Folder"})
    return parent


def setup(payload, headers):
    parent = _folders(["CamelExamples", "EIP"])
    initial = '[{"sensor":"tank-1","value":42,"enabled":true},{"sensor":"tank-2","value":88,"enabled":true},{"sensor":"disabled","value":999,"enabled":false}]'
    for name in ["Input", "High", "Normal", "LastRouted", "AuditLast"]:
        _ensure(parent, {"name": name, "tagType": "AtomicTag", "valueSource": "memory",
            "dataType": "String", "value": initial if name == "Input" else ""})
    return "ready"


def snapshot(payload, headers):
    paths = [_ROOT + "/EIP/" + name for name in ["High", "Normal", "LastRouted", "AuditLast"]]
    values = system.tag.readBlocking(paths)
    return dict((path.rsplit("/", 1)[1], {"value": value.value,
        "quality": str(value.quality)}) for path, value in zip(paths, values))


def writeInput(payload, headers):
    value = system.util.jsonEncode(payload)
    result = system.tag.writeBlocking([_ROOT + "/EIP/Input"], [value])
    if not result[0].isGood():
        raise Exception("Could not write EIP input")
    return "written"
