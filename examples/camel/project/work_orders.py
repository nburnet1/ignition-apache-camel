"""Business beans for the work-order EIP demo, installed as CamelExamples.work_orders.

Only the demo folder is used. Ledger is a memory tag, not a durable work-order DB.
"""
from java.util import ArrayList, LinkedHashMap, List, Map
from java.util.concurrent.locks import ReentrantLock
from org.apache.camel import Processor
from org.apache.camel.processor.aggregate import GroupedBodyAggregationStrategy
import re
import uuid

ROOT = "[default]CamelExamples/WorkOrders"
DEFAULT_MACHINES = ["press-01", "lathe-01", "packer-01"]
SERVICE = "camel-example-work-orders"


def machineNames():
    """Discover machine folders by capability, not a hard-coded machine list."""
    results = system.tag.browse(ROOT + "/Machines", {"tagType": "Folder", "recursive": False}).getResults()
    return sorted(unicode(item["name"]) for item in results
                  if re.match(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", unicode(item["name"]))
                  and all(system.tag.exists(ROOT + "/Machines/" + unicode(item["name"]) + "/" + tag)
                          for tag in ["ActiveWorkOrder", "Status", "Complete"]))


def addMachine(location):
    if not isinstance(location, basestring) or not re.match(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", location):
        raise Exception("Use a machine folder name with letters, digits, underscores or hyphens")
    parent = _ensure(ROOT + "/Machines", {"name": location, "tagType": "Folder"})
    for name, datatype, value in [("ActiveWorkOrder", "String", ""), ("Status", "String", "IDLE"), ("Complete", "Boolean", False)]:
        _ensure(parent, {"name": name, "tagType": "AtomicTag", "valueSource": "memory", "dataType": datatype, "value": value})
    return location


def _py(value):
    if isinstance(value, Map):
        return dict((unicode(entry.getKey()), _py(entry.getValue())) for entry in value.entrySet())
    if isinstance(value, List):
        return [_py(item) for item in value]
    return value


def _java(value):
    if isinstance(value, dict):
        result = LinkedHashMap()
        for key, item in value.items():
            result.put(key, _java(item))
        return result
    if isinstance(value, (list, tuple)):
        result = ArrayList()
        for item in value:
            result.add(_java(item))
        return result
    return value


def _write(paths, values):
    qualities = system.tag.writeBlocking(paths, values)
    if not all(quality.isGood() for quality in qualities):
        raise Exception("Work-order example tag write failed")


def _ensure(parent, definition):
    path = parent + "/" + definition["name"]
    if not system.tag.exists(path):
        qualities = system.tag.configure(parent, [definition], "a")
        if not all(quality.isGood() for quality in qualities):
            raise Exception("Could not create work-order example tag")
    return path


def setup(payload, headers):
    parent = "[default]"
    for name in ["CamelExamples", "WorkOrders"]:
        parent = _ensure(parent, {"name": name, "tagType": "Folder"})
    for name in ["Machines", "Dashboard", "Internal"]:
        _ensure(ROOT, {"name": name, "tagType": "Folder"})
    for location in DEFAULT_MACHINES:
        addMachine(location)
    for name, datatype, value in [("Submitted", "Int4", 0), ("Queued", "Int4", 0), ("Active", "Int4", 0), ("Completed", "Int4", 0), ("Rejected", "Int4", 0),
                                  ("BatchCount", "Int4", 0), ("LastBatchSize", "Int4", 0), ("LastEvent", "String", ""), ("RecentEvents", "String", "[]"), ("Queue", "String", "[]")]:
        _ensure(ROOT + "/Dashboard", {"name": name, "tagType": "AtomicTag", "valueSource": "memory", "dataType": datatype, "value": value})
    _ensure(ROOT + "/Internal", {"name": "Ledger", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "String", "value": "{}"})
    _ensure(ROOT, {"name": "Input", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "String", "value": "[]"})
    _ensure(ROOT, {"name": "CompletionInput", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "String", "value": "{}"})
    return "ready"


def batchFactory(config):
    return GroupedBodyAggregationStrategy()


def serviceFactory(config):
    return WorkOrderService()


class WorkOrderService(Processor):
    def __init__(self):
        self.lock = ReentrantLock()
        value = system.tag.readBlocking([ROOT + "/Internal/Ledger"])[0]
        if not value.quality.isGood():
            raise Exception("Set up work-order tags before starting the service")
        self.data = system.util.jsonDecode(value.value or "{}") or {}
        self.data.setdefault("entries", {})
        self.data.setdefault("sequence", 0)
        self.data.setdefault("batches", 0)
        self.data.setdefault("lastBatchSize", 0)
        self.data.setdefault("events", [])
        self.completing = set()

    def _save(self):
        _write([ROOT + "/Internal/Ledger"], [system.util.jsonEncode(self.data)])

    def _event(self, message, kind, identifier=""):
        self.data["sequence"] += 1
        event = {"sequence": self.data["sequence"], "type": kind, "id": identifier, "at": system.date.now().getTime()}
        message.setHeader("woEvent", system.util.jsonEncode(event))
        self._save()

    def _paths(self, message, location):
        parent = ROOT + "/Machines/" + location
        message.setHeader("woActiveTag", parent + "/ActiveWorkOrder")
        message.setHeader("woStatusTag", parent + "/Status")
        message.setHeader("woCompleteTag", parent + "/Complete")

    def process(self, exchange):
        self.lock.lock()
        try:
            message = exchange.getMessage()
            operation = unicode(message.getHeader("woOperation") or "")
            body = _py(message.getBody())
            result = getattr(self, "op_" + operation.replace("-", "_"))(message, body)
            message.setBody(_java(result))
        finally:
            self.lock.unlock()

    def op_submit(self, message, body):
        if isinstance(body, dict):
            body = [body]
        if not isinstance(body, (list, tuple)) or not 1 <= len(body) <= 100:
            raise Exception("Submit one work-order object or an array containing 1-100 work orders")
        if len(self.data["entries"]) + len(body) > 1000:
            raise Exception("The demo ledger is full (1000 entries)")
        queued = []
        for order in body:
            ticket = uuid.uuid4().hex
            entry = {"ticket": ticket, "order": order, "state": "QUEUED"}
            self.data["entries"][ticket] = entry
            queued.append({"ticket": ticket})
        self._event(message, "submitted", str(len(body)))
        return queued

    def op_recover(self, message, body):
        queued = []
        for entry in self.data["entries"].values():
            if entry["state"] in ["QUEUED", "RESERVED"]:
                entry["state"] = "QUEUED"
                queued.append({"ticket": entry["ticket"]})
        self._event(message, "recovered", str(len(queued)))
        return queued

    def op_batch(self, message, body):
        self.data["batches"] += 1
        self.data["lastBatchSize"] = len(body)
        self._event(message, "batch", str(len(body)))
        return body

    def op_validate(self, message, body):
        ticket = body.get("ticket") if isinstance(body, dict) else None
        entry = self.data["entries"].get(ticket)
        message.setHeader("woDisposition", "SKIP")
        if entry is None or entry["state"] != "QUEUED":
            return body
        message.setHeader("woTicket", ticket)
        order = entry["order"]
        reason = ""
        if not isinstance(order, dict):
            reason = "Work order must be an object"
        else:
            identifier, location, quantity = order.get("id"), order.get("location"), order.get("quantity")
            if not isinstance(identifier, basestring) or not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$", identifier):
                reason = "Invalid work-order id"
            elif location not in machineNames():
                reason = "Unknown machine location"
            else:
                try:
                    numeric = float(quantity)
                    if isinstance(quantity, bool) or not 1 <= numeric <= 100000 or numeric != int(numeric):
                        reason = "Quantity must be an integer from 1 to 100000"
                    else:
                        quantity = int(numeric)
                except (TypeError, ValueError, OverflowError):
                    reason = "Quantity must be an integer from 1 to 100000"
            if not reason and any(other["ticket"] != ticket and other.get("id") == identifier for other in self.data["entries"].values()):
                reason = "Duplicate work-order id"
        if reason:
            entry.update({"state": "REJECTED", "reason": reason})
            message.setHeader("woDisposition", "REJECTED")
            self._event(message, "rejected", unicode(order.get("id", "")) if isinstance(order, dict) else "")
            return {"reason": reason, "order": order}
        entry.update({"id": identifier, "location": location})
        busy = any(other["ticket"] != ticket and other.get("location") == location and other["state"] in ["ACTIVE", "RESERVED"] for other in self.data["entries"].values())
        actual = system.tag.readBlocking([ROOT + "/Machines/" + location + "/ActiveWorkOrder"])[0]
        if not actual.quality.isGood():
            raise Exception("Machine active-order tag is unavailable")
        # Never overwrite a different active order, even if someone edited a tag.
        if actual.value:
            actual_order = system.util.jsonDecode(actual.value)
            busy = busy or actual_order.get("id") != identifier
        if busy:
            message.setHeader("woDisposition", "DEFER")
            self._event(message, "deferred", identifier)
            return {"ticket": ticket}
        entry["state"] = "RESERVED"
        self._paths(message, location)
        message.setHeader("woDisposition", "READY")
        self._save()
        document = dict(order)
        document.update({"id": identifier, "location": location, "quantity": quantity, "state": "ACTIVE"})
        return document

    def op_activated(self, message, body):
        entry = self.data["entries"][unicode(message.getHeader("woTicket"))]
        entry["state"] = "ACTIVE"
        self._event(message, "activated", entry["id"])
        return {"id": entry["id"], "location": entry["location"], "state": "ACTIVE"}

    def op_prepare_completion(self, message, body):
        message.setHeader("woDisposition", "IGNORED")
        if not isinstance(body, dict) or body.get("location") not in machineNames():
            return {"completed": False, "reason": "Unknown machine location"}
        location = body["location"]
        entry = next((entry for entry in self.data["entries"].values() if entry.get("location") == location and entry["state"] == "ACTIVE"), None)
        if entry is None or entry["ticket"] in self.completing:
            return {"completed": False, "reason": "No active work order"}
        if body.get("id") and body["id"] != entry["id"]:
            return {"completed": False, "reason": "Work-order id does not match the active order"}
        actual = system.tag.readBlocking([ROOT + "/Machines/" + location + "/ActiveWorkOrder"])[0]
        if not actual.quality.isGood() or not actual.value or system.util.jsonDecode(actual.value).get("id") != entry["id"]:
            return {"completed": False, "reason": "Active-order tag does not match the ledger"}
        self.completing.add(entry["ticket"])
        self._paths(message, location)
        message.setHeader("woTicket", entry["ticket"])
        message.setHeader("woDisposition", "COMPLETE")
        return body

    def op_completed(self, message, body):
        ticket = unicode(message.getHeader("woTicket"))
        entry = self.data["entries"][ticket]
        entry["state"] = "COMPLETED"
        self.completing.discard(ticket)
        self._event(message, "completed", entry["id"])
        return {"completed": True, "id": entry["id"], "location": entry["location"]}

    def op_metrics(self, message, body):
        encoded = message.getHeader("woEvent")
        if encoded:
            event = system.util.jsonDecode(unicode(encoded))
            events = dict((item["sequence"], item) for item in self.data["events"])
            events[event["sequence"]] = event
            self.data["events"] = sorted(events.values(), key=lambda item: item["sequence"])[-20:]
            self._save()
        entries = self.data["entries"].values()
        recent = self.data["events"]
        values = {"Submitted": len(entries), "Queued": sum(entry["state"] in ["QUEUED", "RESERVED"] for entry in entries),
                  "Active": sum(entry["state"] == "ACTIVE" for entry in entries), "Completed": sum(entry["state"] == "COMPLETED" for entry in entries),
                  "Rejected": sum(entry["state"] == "REJECTED" for entry in entries), "BatchCount": self.data["batches"], "LastBatchSize": self.data["lastBatchSize"],
                  "LastEvent": (recent[-1]["type"] + " " + recent[-1]["id"]) if recent else "",
                  "RecentEvents": system.util.jsonEncode(recent),
                  "Queue": system.util.jsonEncode([entry["order"] for entry in entries if entry["state"] in ["QUEUED", "RESERVED"]])}
        return [{"tagPath": ROOT + "/Dashboard/" + name, "value": value} for name, value in sorted(values.items())]


def snapshot(payload, headers):
    names = ["Submitted", "Queued", "Active", "Completed", "Rejected", "BatchCount", "LastBatchSize", "LastEvent", "RecentEvents", "Queue"]
    paths = [ROOT + "/Dashboard/" + name for name in names]
    values = system.tag.readBlocking(paths)
    if not all(value.quality.isGood() for value in values):
        raise Exception("Dashboard tag quality is bad")
    machines = {}
    for location in machineNames():
        active, status, complete = system.tag.readBlocking([ROOT + "/Machines/" + location + "/" + name for name in ["ActiveWorkOrder", "Status", "Complete"]])
        if not all(value.quality.isGood() for value in [active, status, complete]):
            raise Exception("Machine tag quality is bad")
        machines[location] = {"active": system.util.jsonDecode(active.value) if active.value else None, "status": status.value, "complete": complete.value}
    return {"dashboard": dict((name, value.value) for name, value in zip(names, values)), "machines": machines}


def completeTag(payload, headers):
    location = payload.get("location")
    if location not in machineNames():
        raise Exception("Unknown machine location")
    _write([ROOT + "/Machines/" + location + "/Complete"], [True])
    return "written"


def submitOrders(orders):
    """Gateway script: accept one mapping or a list of work orders."""
    return system.camel.request(SERVICE, "direct:submit-orders", orders)


def submitViaApi(payload, headers):
    return submitOrders(payload)


def sendOrders(orders):
    """InOnly submission; not a durable enqueue guarantee."""
    system.camel.send(SERVICE, "direct:submit-orders", orders)


def completeOrder(location, identifier=None):
    command = {"location": location}
    if identifier is not None:
        command["id"] = identifier
    return system.camel.request(SERVICE, "direct:complete-order", command)


def completeViaApi(payload, headers):
    """HTTP adapter calling the same direct endpoint as Gateway scripts."""
    return completeOrder(payload["location"], payload.get("id"))


def completionFromTag(payload, headers):
    """One native wildcard source; the header identifies the actual changed tag."""
    path = headers["ignitionTagPath"]
    prefix = ROOT + "/Machines/"
    if not path.startswith(prefix) or not path.endswith("/Complete"):
        raise Exception("Unexpected completion tag path")
    location = path[len(prefix):-len("/Complete")]
    if location not in machineNames():
        raise Exception("Unknown machine location")
    active = system.tag.readBlocking([prefix + location + "/ActiveWorkOrder"])[0]
    if not active.quality.isGood():
        raise Exception("Machine active-order tag is unavailable")
    if not active.value:
        _write([path], [False])
        return {"completed": False, "reason": "No active work order"}
    identifier = system.util.jsonDecode(active.value)["id"]
    return completeOrder(location, identifier)


def serviceSnapshot():
    return system.camel.request(SERVICE, "direct:work-order-snapshot", {})


def serviceStatus():
    return _py(system.camel.status(SERVICE))
