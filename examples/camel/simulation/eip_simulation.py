"""Factory business logic. Camel owns transport, scheduling and routing.

Orders and machine reservations are PostgreSQL transactions, not script globals.
Only setup() creates schema, via an explicit provisioning request.
"""
from java.util import ArrayList, LinkedHashMap, List, Map
from org.apache.activemq.artemis.jms.client import ActiveMQConnectionFactory
import uuid
import hashlib

DB = "CamelAcceptance"
ROOT = "[default]EIPSimulation"
RESOURCE = "eip-work-orders"
MACHINES = ["press-01", "lathe-01", "packer-01"]


def py(value):
    if isinstance(value, Map):
        return dict((unicode(e.getKey()), py(e.getValue())) for e in value.entrySet())
    if isinstance(value, List):
        return [py(item) for item in value]
    return value


def java(value):
    if isinstance(value, dict):
        result = LinkedHashMap()
        for key, item in value.items():
            result.put(key, java(item))
        return result
    if isinstance(value, (list, tuple)):
        result = ArrayList()
        for item in value:
            result.add(java(item))
        return result
    return value


def jmsFactory(config):
    return ActiveMQConnectionFactory(config["url"], config["username"], config["password"])


def _update(sql, values=None, tx=None):
    return system.db.runPrepUpdate(sql, values or [], DB, tx)


def _query(sql, values=None, tx=None):
    result = system.db.runPrepQuery(sql, values or [], DB, tx)
    return [dict((name, row[name]) for name in result.getColumnNames()) for row in result]


def _tag(parent, name, datatype=None, value=None):
    path = parent + ("" if parent.endswith("]") else "/") + name
    if not system.tag.exists(path):
        definition = {"name": name, "tagType": "Folder"}
        if datatype:
            definition.update({"tagType": "AtomicTag", "valueSource": "memory", "dataType": datatype, "value": value})
        if not all(q.isGood() for q in system.tag.configure(parent, [definition], "a")):
            raise Exception("Tag creation failed: " + path)
    return path


def setup(payload, headers):
    """Explicit demo installation, never called by validation or a bean factory."""
    _update("CREATE TABLE IF NOT EXISTS eip_sim_orders (id VARCHAR(64) PRIMARY KEY, product VARCHAR(64) NOT NULL, quantity INTEGER NOT NULL, state VARCHAR(16) NOT NULL, machine VARCHAR(64), progress INTEGER NOT NULL DEFAULT 0, created BIGINT NOT NULL)")
    _update("CREATE TABLE IF NOT EXISTS eip_sim_machines (id VARCHAR(64) PRIMARY KEY, active_order VARCHAR(64))")
    _update("CREATE TABLE IF NOT EXISTS eip_sim_events (id BIGSERIAL PRIMARY KEY, kind VARCHAR(64) NOT NULL, body TEXT NOT NULL, created BIGINT NOT NULL)")
    _tag("[default]", "EIPSimulation")
    for folder in ["Machines", "Dashboard", "Tests"]:
        _tag(ROOT, folder)
    for machine in MACHINES:
        _update("INSERT INTO eip_sim_machines (id) VALUES (?) ON CONFLICT (id) DO NOTHING", [machine])
        parent = _tag(ROOT + "/Machines", machine)
        for name, datatype, value in [("ActiveWorkOrder", "String", ""), ("Status", "String", "IDLE"),
                ("Progress", "Int4", 0), ("Quantity", "Int4", 0), ("Complete", "Boolean", False)]:
            _tag(parent, name, datatype, value)
    for name, datatype, value in [("Snapshot", "String", "{}"), ("Generated", "Int8", 0),
            ("Completed", "Int8", 0), ("Available", "Int8", 0), ("LastError", "String", "")]:
        _tag(ROOT + "/Dashboard", name, datatype, value)
    for name, datatype, value in [("Coverage", "String", "[]"), ("Running", "Boolean", False),
            ("Passed", "Int4", 0), ("Total", "Int4", 64), ("LastRun", "String", "Not run"),
            ("TagProbe", "String", ""), ("TagObserved", "String", "")]:
        _tag(ROOT + "/Tests", name, datatype, value)
    return {"ready": True, "database": DB, "machines": MACHINES}


def event(kind, body, tx=None):
    _update("INSERT INTO eip_sim_events (kind, body, created) VALUES (?, ?, ?)",
            [kind, system.util.jsonEncode(body), system.date.now().getTime()], tx)


def generate(payload, headers):
    """Produce a document; the JMS consumer persists it idempotently."""
    return {"id": "WO-" + uuid.uuid4().hex[:12].upper(), "product": "Part-" + str(1 + system.date.getSecond(system.date.now()) % 5),
            "quantity": 10 + system.date.getSecond(system.date.now()) % 11, "format": "work-order.v1"}


def accept(payload, headers):
    if isinstance(payload, basestring):
        payload = system.util.jsonDecode(payload)
    if not isinstance(payload, dict) or payload.get("format") != "work-order.v1":
        raise ValueError("A work-order.v1 document is required")
    quantity = payload.get("quantity")
    if isinstance(quantity, bool) or not isinstance(quantity, (int, long)) or not 1 <= quantity <= 10000:
        raise ValueError("Quantity must be an integer from 1 to 10000")
    changed = _update("INSERT INTO eip_sim_orders (id, product, quantity, state, created) VALUES (?, ?, ?, 'AVAILABLE', ?) ON CONFLICT (id) DO NOTHING",
        [payload["id"], payload["product"], quantity, system.date.now().getTime()])
    if changed:
        event("created", payload)
    return {"accepted": bool(changed), "id": payload["id"]}


def start(payload, headers):
    machine, identifier = payload.get("machine"), payload.get("id")
    if machine not in MACHINES or not identifier:
        return {"ok": False, "message": "Select an available work order and a machine."}
    tx = system.db.beginTransaction(DB)
    try:
        locked = _query("SELECT active_order FROM eip_sim_machines WHERE id=? FOR UPDATE", [machine], tx)[0]
        order = _query("SELECT * FROM eip_sim_orders WHERE id=? FOR UPDATE", [identifier], tx)
        if locked["active_order"] or not order or order[0]["state"] != "AVAILABLE":
            system.db.rollbackTransaction(tx)
            return {"ok": False, "message": "Machine is busy or the work order is no longer available."}
        _update("UPDATE eip_sim_machines SET active_order=? WHERE id=?", [identifier, machine], tx)
        _update("UPDATE eip_sim_orders SET machine=?, state='RUNNING', progress=0 WHERE id=?", [machine, identifier], tx)
        event("started", {"id": identifier, "machine": machine}, tx)
        system.db.commitTransaction(tx)
    except:
        system.db.rollbackTransaction(tx)
        raise
    finally:
        system.db.closeTransaction(tx)
    return {"ok": True, "message": identifier + " started on " + machine, "id": identifier, "machine": machine}


def complete(payload, headers):
    machine, identifier = payload.get("machine"), payload.get("id")
    if machine not in MACHINES or not identifier:
        return {"ok": False, "message": "Exact machine and work order required"}
    tx = system.db.beginTransaction(DB)
    try:
        locked = _query("SELECT active_order FROM eip_sim_machines WHERE id=? FOR UPDATE", [machine], tx)[0]
        if locked["active_order"] != identifier:
            system.db.rollbackTransaction(tx)
            return {"ok": False, "message": "Stale or duplicate completion ignored"}
        _update("UPDATE eip_sim_orders SET state='COMPLETED', progress=quantity WHERE id=? AND state='RUNNING'", [identifier], tx)
        _update("UPDATE eip_sim_machines SET active_order=NULL WHERE id=?", [machine], tx)
        event("completed", payload, tx)
        system.db.commitTransaction(tx)
    except:
        system.db.rollbackTransaction(tx)
        raise
    finally:
        system.db.closeTransaction(tx)
    return {"ok": True, "message": identifier + " completed", "id": identifier, "machine": machine}


def advance(payload, headers):
    _update("UPDATE eip_sim_orders SET progress=LEAST(quantity, progress+1) WHERE state='RUNNING'")
    return projectTags({}, {})


def snapshot(payload, headers):
    orders = _query("SELECT * FROM eip_sim_orders WHERE state <> 'COMPLETED' ORDER BY created DESC LIMIT 250")
    available = [dict(value=order["id"], label=order["id"] + " | " + order["product"] + " | " + str(order["quantity"])) for order in orders if order["state"] == "AVAILABLE"]
    machines = []
    for machine in MACHINES:
        active = next((order for order in orders if order["machine"] == machine and order["state"] == "RUNNING"), None)
        machines.append({"machine": machine, "status": "RUNNING" if active else "IDLE", "workOrder": active["id"] if active else "",
            "progress": active["progress"] if active else 0, "quantity": active["quantity"] if active else 0})
    totals = _query("SELECT COUNT(*) AS generated, COUNT(*) FILTER (WHERE state='COMPLETED') AS completed, COUNT(*) FILTER (WHERE state='AVAILABLE') AS available FROM eip_sim_orders")[0]
    return {"orders": available, "machines": machines, "totals": totals,
        "updated": system.date.now().getTime(), "recent": _query("SELECT id, kind, body, created FROM eip_sim_events ORDER BY id DESC LIMIT 15")}


def projectTags(payload, headers):
    report = snapshot({}, {})
    updates = [{"path": ROOT + "/Dashboard/Snapshot", "value": system.util.jsonEncode(report)}]
    for key in ["generated", "completed", "available"]:
        updates.append({"path": ROOT + "/Dashboard/" + key.title(), "value": report["totals"][key]})
    for machine in report["machines"]:
        for tag, key in [("ActiveWorkOrder", "workOrder"), ("Status", "status"), ("Progress", "progress"), ("Quantity", "quantity")]:
            updates.append({"path": ROOT + "/Machines/" + machine["machine"] + "/" + tag, "value": machine[key]})
        updates.append({"path": ROOT + "/Machines/" + machine["machine"] + "/Complete",
            "value": machine["status"] == "RUNNING" and machine["progress"] >= machine["quantity"]})
    return updates


def completionSignal(payload, headers):
    path = unicode(headers["ignitionTagPath"])
    machine = path.split("/")[-2]
    active = _query("SELECT active_order FROM eip_sim_machines WHERE id=?", [machine])
    return {"machine": machine, "id": active[0]["active_order"] if active else None}


def subscriptions(payload, headers):
    """Only real browser sessions; designer pseudo-sessions are not target UUIDs."""
    result = []
    for item in system.perspective.getSessionInfo():
        if item["project"] != "global" or item.get("sessionScope") == "designer":
            continue
        identifier = unicode(item["id"])
        uuid.UUID(identifier)
        result.append({"session": identifier, "message": {"updated": system.date.now().getTime()}})
    return result


def mqttFrame(payload, headers):
    path = unicode(headers["ignitionTagPath"])
    def segment(value):
        return "".join(chr(n) if 97 <= n <= 122 or 65 <= n <= 90 or 48 <= n <= 57 or n in [45, 95] else "%%%02X" % n for n in bytearray(value.encode("utf-8")))
    topic = "ignition/default/" + "/".join(segment(part) for part in path[len("[default]"):].split("/"))
    return {"topic": topic, "message": system.util.jsonEncode({"tagPath": path, "value": payload,
        "quality": headers["ignitionTagQuality"], "timestamp": headers["ignitionTagTimestamp"]})}


def tagProbe(payload, headers):
    """Return update pairs so the native ignition-tag producer does the writes."""
    return [{"path": ROOT + "/Tests/TagProbe", "value": payload["value"]}]


def tagInventory(payload, headers):
    paths = sorted(unicode(item["fullPath"]) for item in system.tag.browse("[default]", {"recursive": True, "tagType": "AtomicTag"}).getResults())
    offset = max(0, int(payload.get("offset", 0)))
    return {"count": len(paths), "pathDigest": hashlib.sha256("\n".join(paths).encode("utf-8")).hexdigest(),
        "offset": offset, "pathHashes": [hashlib.sha256(path.encode("utf-8")).hexdigest() for path in paths[offset:offset+256]]}


def observeProbe(payload, headers):
    return [{"path": ROOT + "/Tests/TagObserved", "value": payload}]


def coverage(payload, headers):
    paths = [ROOT + "/Tests/" + name for name in ["Coverage", "Running", "Passed", "LastRun", "TagObserved"]]
    values = system.tag.readBlocking(paths)
    if not all(v.quality.isGood() for v in values):
        raise Exception("Test status tags are unavailable")
    return {"patterns": system.util.jsonDecode(values[0].value), "running": values[1].value,
            "passed": values[2].value, "lastRun": values[3].value, "tagObserved": values[4].value}
