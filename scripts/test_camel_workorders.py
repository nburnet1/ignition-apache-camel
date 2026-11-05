"""SDK-free tests for the example's bean; real Camel EIPs are tested on 9288."""
import json
import runpy
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class JavaMap(dict):
    def put(self, key, value):
        self[key] = value

    def entrySet(self):
        return [types.SimpleNamespace(getKey=lambda key=key: key, getValue=lambda value=value: value) for key, value in self.items()]


class JavaList(list):
    def add(self, value):
        self.append(value)


class Lock:
    def __init__(self):
        self.value = threading.RLock()

    def lock(self):
        self.value.acquire()

    def unlock(self):
        self.value.release()


class Tags:
    def __init__(self):
        self.values = {}
        self.good = types.SimpleNamespace(isGood=lambda: True)

    def exists(self, path):
        return path in self.values

    def configure(self, parent, definitions, policy):
        for definition in definitions:
            path = parent + "/" + definition["name"]
            assert path not in self.values, "Setup must never overwrite an existing tag"
            self.values[path] = definition.get("value")
        return [self.good] * len(definitions)

    def readBlocking(self, paths):
        return [types.SimpleNamespace(value=self.values[path], quality=self.good) for path in paths]

    def writeBlocking(self, paths, values):
        for path, value in zip(paths, values):
            assert path in self.values
            self.values[path] = value
        return [self.good] * len(paths)

    def browse(self, parent, options):
        prefix = parent + "/"
        children = [{"name": path[len(prefix):], "tagType": "Folder"} for path, value in self.values.items()
                    if path.startswith(prefix) and "/" not in path[len(prefix):] and value is None]
        return types.SimpleNamespace(getResults=lambda: children)


class Message:
    def __init__(self, operation, body, headers=None):
        self.headers = dict(headers or {}, woOperation=operation)
        self.body = body

    def getHeader(self, key):
        return self.headers.get(key)

    def setHeader(self, key, value):
        self.headers[key] = value

    def getBody(self):
        return self.body

    def setBody(self, value):
        self.body = value


class WorkOrderBeanTest(unittest.TestCase):
    def setUp(self):
        modules = {name: types.ModuleType(name) for name in ["java", "java.util", "java.util.concurrent", "java.util.concurrent.locks", "org", "org.apache", "org.apache.camel", "org.apache.camel.processor", "org.apache.camel.processor.aggregate"]}
        modules["java.util"].ArrayList = JavaList
        modules["java.util"].LinkedHashMap = JavaMap
        modules["java.util"].List = JavaList
        modules["java.util"].Map = JavaMap
        modules["java.util.concurrent.locks"].ReentrantLock = Lock
        modules["org.apache.camel"].Processor = object
        modules["org.apache.camel.processor.aggregate"].GroupedBodyAggregationStrategy = object
        self.tags = Tags()
        system = types.SimpleNamespace(tag=self.tags, util=types.SimpleNamespace(jsonEncode=json.dumps, jsonDecode=json.loads),
                                       date=types.SimpleNamespace(now=lambda: types.SimpleNamespace(getTime=lambda: 123456789)))
        source = Path(__file__).resolve().parents[1] / "examples/camel/project/work_orders.py"
        with patch.dict("sys.modules", modules):
            self.code = runpy.run_path(str(source), init_globals={"system": system, "unicode": str, "long": int, "basestring": str})
        self.code["setup"]({}, {})
        self.service = self.code["WorkOrderService"]()

    def call(self, operation, body=None, headers=None):
        message = Message(operation, body, headers)
        self.service.process(types.SimpleNamespace(getMessage=lambda: message))
        return message

    def submit(self, *orders):
        return self.call("submit", list(orders)).body

    def validate(self, wrapper):
        return self.call("validate", wrapper)

    def activate(self, message):
        self.assertEqual("READY", message.headers["woDisposition"])
        self.tags.writeBlocking([message.headers["woActiveTag"], message.headers["woStatusTag"]], [json.dumps(message.body), "ACTIVE"])
        return self.call("activated", headers=message.headers)

    def metrics(self, event=None):
        return {item["tagPath"].rsplit("/", 1)[1]: item["value"] for item in self.call("metrics", headers=event.headers if event else None).body}

    def test_busy_machine_defers_then_completes_without_double_counting(self):
        first, second = self.submit({"id": "WO-1", "location": "press-01", "quantity": 5}, {"id": "WO-2", "location": "press-01", "quantity": 10})
        reservation = self.validate(first)
        self.assertEqual("DEFER", self.validate(second).headers["woDisposition"], "Reservation must protect a slot even before its tag write finishes")
        self.activate(reservation)
        self.assertEqual("DEFER", self.validate(second).headers["woDisposition"])
        self.assertEqual("IGNORED", self.call("prepare-completion", {"id": "wrong", "location": "press-01"}).headers["woDisposition"])
        completion = self.call("prepare-completion", {"id": "WO-1", "location": "press-01"})
        self.assertEqual("COMPLETE", completion.headers["woDisposition"])
        self.assertEqual("IGNORED", self.call("prepare-completion", {"location": "press-01"}).headers["woDisposition"])
        self.tags.writeBlocking([completion.headers["woActiveTag"], completion.headers["woStatusTag"], completion.headers["woCompleteTag"]], ["", "IDLE", False])
        completed = self.call("completed", headers=completion.headers)
        self.assertTrue(completed.body["completed"])
        self.assertEqual("IGNORED", self.call("prepare-completion", {"location": "press-01"}).headers["woDisposition"])
        self.activate(self.validate(second))
        counts = self.metrics(completed)
        self.assertEqual((2, 1, 1, 0), (counts["Submitted"], counts["Active"], counts["Completed"], counts["Queued"]))

    def test_validation_rejects_bad_locations_quantities_and_duplicate_ids(self):
        good, invalid_location, invalid_quantity, duplicate = self.submit(
            {"id": "WO-1", "location": "lathe-01", "quantity": 2},
            {"id": "WO-2", "location": "../other", "quantity": 2},
            {"id": "WO-3", "location": "packer-01", "quantity": True},
            {"id": "WO-1", "location": "press-01", "quantity": 2})
        self.activate(self.validate(good))
        for item in [invalid_location, invalid_quantity, duplicate]:
            self.assertEqual("REJECTED", self.validate(item).headers["woDisposition"])
        counts = self.metrics()
        self.assertEqual((4, 1, 3, 0), (counts["Submitted"], counts["Active"], counts["Rejected"], counts["Queued"]))

    def test_wiretap_projects_current_counts_even_when_events_arrive_out_of_order(self):
        submitted = self.call("submit", [{"id": "WO-1", "location": "press-01", "quantity": 1}])
        activated = self.activate(self.validate(submitted.body[0]))
        self.metrics(activated)
        result = self.metrics(submitted)
        self.assertEqual("activated WO-1", result["LastEvent"])
        self.assertEqual(1, result["Active"])
        self.assertEqual(0, result["Queued"])
        self.metrics(activated)
        self.assertEqual(2, len(self.service.data["events"]), "Repeated audit delivery must not duplicate an event")

    def test_context_recovery_only_requeues_pending_orders_and_setup_preserves_tags(self):
        first, second = self.submit({"id": "WO-1", "location": "press-01", "quantity": 1}, {"id": "WO-2", "location": "lathe-01", "quantity": 1})
        self.activate(self.validate(first))
        self.validate(second)  # An assignment interrupted after reserving the slot.
        self.service = self.code["WorkOrderService"]()
        recovered = self.call("recover").body
        self.assertEqual([second], recovered)
        before = self.tags.values.copy()
        self.code["setup"]({}, {})
        self.assertEqual(before, self.tags.values)

    def test_new_machine_is_discovered_and_extra_order_fields_survive(self):
        self.code["addMachine"]("drill-04")
        self.assertIn("drill-04", self.code["machineNames"]())
        queued = self.call("submit", {"id": "WO-DYNAMIC", "location": "drill-04", "quantity": "3", "sku": "widget", "custom": {"color": "blue"}})
        ready = self.validate(queued.body[0])
        self.assertEqual("READY", ready.headers["woDisposition"])
        self.assertEqual(3, ready.body["quantity"])
        self.assertEqual("widget", ready.body["sku"])
        self.assertEqual({"color": "blue"}, ready.body["custom"])
        self.activate(ready)
        self.tags.values[ready.headers["woCompleteTag"]] = True
        calls = []
        system = self.code["completionFromTag"].__globals__["system"]
        system.camel = types.SimpleNamespace(request=lambda *args: calls.append(args) or {"completed": True})
        self.code["completionFromTag"](True, {"ignitionTagPath": ready.headers["woCompleteTag"]})
        self.assertEqual("direct:complete-order", calls[0][1])
        self.assertEqual({"location": "drill-04", "id": "WO-DYNAMIC"}, calls[0][2])

    def test_idle_completion_signal_is_reset_and_foreign_paths_are_rejected(self):
        path = self.code["ROOT"] + "/Machines/press-01/Complete"
        self.tags.values[path] = True
        result = self.code["completionFromTag"](True, {"ignitionTagPath": path})
        self.assertFalse(result["completed"])
        self.assertFalse(self.tags.values[path])
        with self.assertRaises(Exception):
            self.code["completionFromTag"](True, {"ignitionTagPath": "[default]Production/Complete"})

    def test_gateway_helpers_use_the_system_camel_api(self):
        calls = []
        system = self.code["submitOrders"].__globals__["system"]
        system.camel = types.SimpleNamespace(
            request=lambda *args: calls.append(("request", args)) or {"ok": True},
            send=lambda *args: calls.append(("send", args)),
            status=lambda *args: calls.append(("status", args)) or {"state": "RUNNING"})
        order = {"id": "WO-API", "location": "press-01", "quantity": 2}
        self.assertEqual({"ok": True}, self.code["submitViaApi"](order, {}))
        self.code["sendOrders"]([order])
        self.code["completeViaApi"]({"location": "press-01", "id": "WO-API"}, {})
        self.code["serviceSnapshot"]()
        self.code["serviceStatus"]()
        self.assertEqual(["request", "send", "request", "request", "status"], [call[0] for call in calls])
        self.assertEqual("direct:submit-orders", calls[0][1][1])
        self.assertEqual("direct:complete-order", calls[2][1][1])
        self.assertEqual("direct:work-order-snapshot", calls[3][1][1])


if __name__ == "__main__":
    unittest.main()
