"""Setup/inspection helpers only; Control Bus routing is entirely YAML.

Installed as CamelExamples.control_bus alongside the other demo libraries.
"""
_ROOT = "[default]CamelExamples/ControlBus"


def setup(payload, headers):
    parent = "[default]"
    for name in ["CamelExamples", "ControlBus"]:
        path = parent + "/" + name
        if not system.tag.exists(path):
            results = system.tag.configure(parent, [{"name": name, "tagType": "Folder"}], "a")
            if not all(result.isGood() for result in results):
                raise Exception("Could not create Control Bus example folder")
        parent = path
    for name, datatype, value in [("Heartbeat", "String", ""), ("TickCount", "Int8", 0), ("RouteState", "String", ""), ("Command", "String", "status")]:
        if not system.tag.exists(_ROOT + "/" + name):
            results = system.tag.configure(_ROOT, [{"name": name, "tagType": "AtomicTag", "valueSource": "memory", "dataType": datatype, "value": value}], "a")
            if not all(result.isGood() for result in results):
                raise Exception("Could not create Control Bus example tag")
    return "ready"


def snapshot(payload, headers):
    names = ["Heartbeat", "TickCount", "RouteState", "Command"]
    values = system.tag.readBlocking([_ROOT + "/" + name for name in names])
    return dict((name, {"value": value.value, "quality": str(value.quality)}) for name, value in zip(names, values))


def command(payload, headers):
    if payload not in ["suspend", "resume", "status"]:
        raise Exception("Use suspend, resume or status")
    results = system.tag.writeBlocking([_ROOT + "/Command"], [payload])
    if not results[0].isGood():
        raise Exception("Control Bus command tag write failed")
    return "written"
