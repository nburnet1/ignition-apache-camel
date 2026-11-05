def setup(payload, headers):
    tags = [
        {"name": "CamelInput", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int8", "value": 0},
        {"name": "CamelOutput", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int8", "value": 0},
        {"name": "CamelReloadProof", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int4", "value": 0},
        {"name": "CamelHttpNumber", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int8", "value": 0},
        {"name": "CamelMqtt5Number", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int8", "value": 0},
        {"name": "CamelMqtt5Received", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int4", "value": 0},
        {"name": "CamelMqtt5LastMessage", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "String", "value": ""},
    ]
    results = system.tag.configure("[default]", tags, "m")
    if not all(result.isGood() for result in results):
        raise Exception("Could not configure Camel acceptance tags: " + str(results))
    return payload


def transform(payload, headers):
    if headers["ignitionTagPath"] != "[default]CamelInput" or not headers["ignitionTagGood"]:
        raise Exception("Missing or bad tag headers")
    return payload + 1


def observe(payload, headers):
    if headers["ignitionTagPath"] != "[default]CamelOutput" or not headers["ignitionTagGood"]:
        raise Exception("Missing or bad output quality")
    if payload != 9007199254740994:
        raise Exception("Integer precision or transform failed: " + str(payload))
    return payload


def jythonProof(payload, headers):
    if payload != 42:
        raise Exception("Native Jython DSL processor did not produce 42: " + str(payload))
    return payload


def recordReload(payload, headers):
    result = system.tag.writeBlocking(["[default]CamelReloadProof"], [payload])
    if not result[0].isGood():
        raise Exception("Project reload proof write failed: " + str(result))
    return payload


def assertReload2(payload, headers):
    if payload != 2:
        raise Exception("Expected updated project definition to deliver 2")
    return payload


def fail(payload, headers):
    raise Exception("Intentional Camel scripting API delivery failure")


def verifyCamelApi(payload, headers):
    verifyCamelHints(payload, headers)
    name = "camel-acceptance-api-target"
    if name not in system.camel.list():
        raise Exception("system.camel.list omitted its target")
    if system.camel.status(name)["state"] != "RUNNING":
        raise Exception("system.camel.status did not report RUNNING")
    values = [9007199254740993, None, [1, "two"], {"value": 9007199254740993}]
    for value in values:
        result = system.camel.request(name, "direct:echo", value)
        if result != value:
            raise Exception("system.camel.request changed its payload: " + str(result))
    result = system.camel.request(name=name, endpoint="direct:header", payload=None,
                                  headers={"proof": 9007199254740993})
    if result != 9007199254740993:
        raise Exception("system.camel.request lost its header or integer precision")
    system.camel.send(name, "direct:send", 42)
    for target in ["camel-acceptance-missing", "camel-acceptance-disabled"]:
        try:
            system.camel.request(target, "direct:echo", None)
        except:
            pass
        else:
            raise Exception("Unavailable target was accepted: " + target)
    try:
        system.camel.request(name, "direct:fail", None)
    except:
        pass
    else:
        raise Exception("Delivery failure was swallowed")
    system.camel.restart(name)
    return "verified"


def verifyCamelHints(payload, headers):
    # This is the same Gateway tree served by AutocompleteRpc to Designer.
    from com.inductiveautomation.ignition.gateway import IgnitionGateway
    root = IgnitionGateway.get().getScriptManager().getHintsTree()
    camel = root.children().get("system").children().get("camel")
    if camel is None:
        raise Exception("system.camel is missing from Gateway completion hints")
    names = sorted(method.getName() for method in camel.methods())
    if names != ["list", "request", "restart", "send", "status"]:
        raise Exception("Incomplete system.camel completion hints: " + str(names))
    for method in camel.methods():
        if not method.getDescription():
            raise Exception("Missing system.camel documentation: " + method.getName())
    return names


def httpNumber(payload, headers):
    if headers["CamelHttpMethod"] != "POST" or headers["CamelHttpPath"] != "/camel-acceptance-http/number":
        raise Exception("Incorrect server-generated HTTP metadata")
    if not headers.get("ignitionHttpActor"):
        raise Exception("Missing authenticated Gateway actor")
    forbidden = ["authorization", "cookie", "x-csrf-token", "x-client-secret", "camelhttpresponsecode"]
    if any(str(key).lower() in forbidden for key in headers):
        raise Exception("HTTP request credentials or untrusted headers leaked into Camel")
    return int(payload["number"]) + 1


def httpResponse(payload, headers):
    actual = system.tag.readBlocking(["[default]CamelHttpNumber"])[0]
    if not actual.quality.isGood() or actual.value != payload:
        raise Exception("HTTP route's tag write was not observable")
    return {"number": payload, "writeVerified": True, "quality": str(actual.quality)}


def mqtt5Transform(payload, headers):
    if headers["CamelMqttTopic"] != "apache-camel/camel/mqtt5-proof":
        raise Exception("Native MQTT 5 consumer did not provide its MQTT topic")
    if int(headers["CamelMqttQoS"]) != 1:
        raise Exception("Native MQTT 5 consumer did not receive QoS 1")
    return payload["number"] + 1


def mqtt5Observe(payload, headers):
    value, count = system.tag.readBlocking(["[default]CamelMqtt5Number", "[default]CamelMqtt5Received"])
    if not value.quality.isGood() or value.value != payload or not count.quality.isGood():
        raise Exception("Native MQTT 5 -> script -> tag proof failed")
    if payload not in [9007199254740994, 7002]:
        raise Exception("Native MQTT 5 proof changed integer precision or delivered an unexpected value: " + str(payload))
    report = system.util.jsonEncode({"number": payload, "topic": headers["CamelMqttTopic"], "quality": str(value.quality)})
    results = system.tag.writeBlocking(["[default]CamelMqtt5Received", "[default]CamelMqtt5LastMessage"], [int(count.value) + 1, report])
    if not all(result.isGood() for result in results):
        raise Exception("Could not record native MQTT 5 proof")
    return payload


def mqtt5Snapshot(payload, headers):
    names = ["CamelMqtt5Number", "CamelMqtt5Received", "CamelMqtt5LastMessage"]
    values = system.tag.readBlocking(["[default]" + name for name in names])
    if not all(value.quality.isGood() for value in values):
        raise Exception("MQTT 5 proof tags have bad quality")
    return dict((name, value.value) for name, value in zip(names, values))
