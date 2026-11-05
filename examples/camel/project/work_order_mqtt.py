"""WorkOrders -> named MQTT component -> isolated, typed mirror tags."""
from java.util.concurrent.locks import ReentrantLock
import re

SOURCE = "[default]CamelExamples/WorkOrders"
MIRROR = "[default]CamelExamples/WorkOrdersMqttMirror"
TOPIC = "camel-demo/work-orders/"
TYPES = ("Boolean", "String", "Int1", "Int2", "Int4", "Int8", "Float4", "Float8")
_lock = ReentrantLock()


def _segment(value):
    encoded = []
    for number in bytearray(unicode(value).encode("utf-8")):
        encoded.append(chr(number) if 97 <= number <= 122 or 48 <= number <= 57 or number == 45 else "_%02X" % number)
    return "".join(encoded)


def _unsegment(value):
    decoded = re.sub(r"_([0-9A-F]{2})", lambda match: unichr(int(match.group(1), 16)), unicode(value)).encode("latin-1").decode("utf-8")
    # Keep decoded names safe when used in a dynamic Camel endpoint URI too;
    # percent escapes or URI delimiters must not become a second decoding path.
    if not re.match(r"^[\w -]+\Z", decoded, re.UNICODE) or _segment(decoded) != value:
        raise Exception("Invalid work-order MQTT topic segment")
    return decoded


def _relative(path):
    prefix = SOURCE + "/"
    if not path.startswith(prefix) or path.startswith(MIRROR + "/"):
        raise Exception("Tag is outside the WorkOrders source folder")
    return path[len(prefix):]


def _datatype(path):
    config = system.tag.getConfiguration(path, False)
    if unicode(config[0].get("tagType", "AtomicTag")) != "AtomicTag":
        return None
    datatype = unicode(config[0].get("dataType", "Int4"))
    if datatype not in TYPES:
        raise Exception("This demo mirrors scalar Boolean/String/numeric tags, not " + datatype)
    return datatype


def _ensure(parent, name, datatype=None):
    path = parent + ("" if parent.endswith("]") else "/") + name
    if not system.tag.exists(path):
        definition = {"name": name, "tagType": "Folder"} if datatype is None else {
            "name": name, "tagType": "AtomicTag", "valueSource": "memory", "dataType": datatype,
            "value": "" if datatype == "String" else False if datatype == "Boolean" else 0}
        qualities = system.tag.configure(parent, [definition], "a")
        if not all(quality.isGood() for quality in qualities):
            raise Exception("Could not create work-order mirror tag")
    elif datatype is not None and _datatype(path) != datatype:
        raise Exception("Existing mirror tag datatype differs: " + path)
    return path


def encodeTag(payload, headers):
    path = headers["ignitionTagPath"]
    if path == SOURCE:
        return None
    relative = _relative(path)
    datatype = _datatype(path)
    if datatype is None:
        return None
    message = {"version": 1, "dataType": datatype, "value": payload,
               "quality": headers["ignitionTagQuality"], "timestamp": headers["ignitionTagTimestamp"]}
    return {"topic": TOPIC + "/".join(_segment(part) for part in relative.split("/")),
            "message": system.util.jsonEncode(message)}


def prepareMirror(payload, headers):
    topic = unicode(headers.get("CamelMqttTopic", ""))
    if not topic.startswith(TOPIC):
        raise Exception("Unexpected work-order MQTT topic")
    parts = [_unsegment(part) for part in topic[len(TOPIC):].split("/")]
    message = system.util.jsonDecode(payload)
    if message.get("version") != 1 or message.get("dataType") not in TYPES or "value" not in message:
        raise Exception("Invalid work-order MQTT frame")
    _lock.lock()
    try:
        parent = _ensure("[default]", "CamelExamples")
        parent = _ensure(parent, "WorkOrdersMqttMirror")
        for part in parts[:-1]:
            parent = _ensure(parent, part)
        path = _ensure(parent, parts[-1], message["dataType"])
    finally:
        _lock.unlock()
    return {"tagPath": path, "value": message["value"]}


def snapshot(payload, headers):
    nodes = system.tag.browse(SOURCE, {"recursive": True, "tagType": "AtomicTag"}).getResults()
    paths = sorted(unicode(node["fullPath"]) for node in nodes)
    mirrors = [MIRROR + "/" + _relative(path) for path in paths]
    values = system.tag.readBlocking(paths + mirrors)
    count = len(paths)
    mismatches = [_relative(path) for index, path in enumerate(paths)
                  if not values[index].quality.isGood() or not values[count + index].quality.isGood()
                  or values[index].value != values[count + index].value]
    samples = {}
    for relative in ("Dashboard/Completed", "Machines/press-01/Complete", "Machines/press-01/Status", "TransportProbe/Message", "TransportProbe/Flag", "TransportProbe/Count"):
        if SOURCE + "/" + relative in paths:
            index = paths.index(SOURCE + "/" + relative)
            samples[relative] = {"source": values[index].value, "mirror": values[count + index].value,
                                 "quality": unicode(values[count + index].quality),
                                 "dataType": _datatype(mirrors[index]) if values[count + index].quality.isGood() else None}
    return {"sourceCount": count, "matchedCount": count - len(mismatches), "mismatches": mismatches,
            "sourceRoot": SOURCE, "mirrorRoot": MIRROR, "topicPrefix": TOPIC, "samples": samples}


def probe(payload, headers):
    """Exercise harmless transport-only tags, never a machine command or ledger."""
    parent = _ensure(SOURCE, "TransportProbe")
    names = [("Message", "String", payload["message"]), ("Flag", "Boolean", payload["flag"]), ("Count", "Int4", payload["count"])]
    paths = [_ensure(parent, name, datatype) for name, datatype, value in names]
    qualities = system.tag.writeBlocking(paths, [value for name, datatype, value in names])
    if not all(quality.isGood() for quality in qualities):
        raise Exception("Could not write transport probe tags")
    return "written"
