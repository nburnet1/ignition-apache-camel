"""Benchmark real native components on 9288; preserve global and the running factory.

Adds only owned project resources. No trial resets, Gateway restarts, conversion
changes, fake SDK services or replacement transports. Results include all errors.
"""
import argparse
import concurrent.futures
import datetime
import http.client
import io
import json
import math
import os
import runpy
import subprocess
import threading
import time
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
g = runpy.run_path(str(ROOT / "scripts/camel-acceptance.py"))
p = runpy.run_path(str(ROOT / "scripts/camel-example-project.py"))
api, wait = g["api"], g["wait"]
NAME = "camel-component-benchmark"
PREFIX = "com.noahburnette.ignition.apachecamel/camel-route"
ENDPOINT = "/system/integrations/" + NAME
PAGE_CONFIG = "com.inductiveautomation.perspective/page-config/config.json"


def files(archive):
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        return {entry.filename: source.read(entry) for entry in source.infolist()}


def pack(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for name, content in entries.items():
            output.writestr(name, content)
    return buffer.getvalue()


def resources():
    result = {}
    def add(path, contents, attributes=None, scope="G"):
        result[path + "/resource.json"] = json.dumps({"scope": scope, "version": 1,
            "restricted": False, "overridable": True, "files": list(contents), "attributes": attributes or {}}).encode()
        for name, value in contents.items():
            result[path + "/" + name] = value if isinstance(value, bytes) else value.encode()
    add("ignition/script-python/component_benchmark", {"code.py": (ROOT / "benchmarks/ignition-components/project/component_benchmark.py").read_bytes()}, {"hintScope": 1}, "A")
    add("ignition/message/camelComponentBenchmarkEcho", {"handleMessage.py": "def handleMessage(payload):\n\treturn payload\n"}, {"enabled": True, "threadType": "Dedicated"})
    add("ignition/message/camelComponentBenchmarkDelivered", {"handleMessage.py": 'def handleMessage(payload):\n\tsystem.camel.send("' + NAME + '", "direct:message-delivered", payload)\n'}, {"enabled": True, "threadType": "Dedicated"})
    view = {"custom": {}, "params": {}, "props": {"defaultSize": {"width": 600, "height": 200}}, "root": {
        "type": "ia.container.flex", "meta": {"name": "root"}, "props": {"direction": "column"}, "children": [
            {"type": "ia.display.label", "meta": {"name": "SessionId"}, "props": {"text": "Waiting"},
             "propConfig": {"props.text": {"binding": {"type": "property", "config": {"path": "session.props.id"}}}}},
            {"type": "ia.display.label", "meta": {"name": "Receipt"}, "props": {"text": "Waiting for a native benchmark message"},
             "scripts": {"messageHandlers": [{"messageType": "camelComponentBenchmark", "sessionScope": True,
                 "pageScope": False, "viewScope": False, "script": '\tself.props.text = str(payload["sequence"])\n\tsystem.camel.send("' + NAME + '", "direct:perspective-delivered", payload)'}]}}
        ]}}
    add("com.inductiveautomation.perspective/views/ComponentBenchmark", {"view.json": json.dumps(view)})
    return result


def provision(output):
    assert g["find"](NAME) is None and g["find"](NAME + "-setup") is None, "A benchmark route already exists; review before replacing it"
    before_archive = p["export_project"](g, "global")
    assert before_archive, "This benchmark expects the existing runnable global project"
    before = files(before_archive)
    added = resources()
    assert not set(added).intersection(before), "Benchmark project resources already exist; refusing to overwrite"
    assert PAGE_CONFIG in before, "Expected the existing global Perspective page configuration"
    pages = json.loads(before[PAGE_CONFIG])
    assert "/component-benchmark" not in pages["pages"], "Benchmark page already exists"
    pages["pages"]["/component-benchmark"] = {"viewPath": "ComponentBenchmark", "title": "Component benchmark", "docks": {}}
    replacement_page = json.dumps(pages).encode()
    after = dict(before, **added)
    after[PAGE_CONFIG] = replacement_page
    (output / "global-before.zip").write_bytes(before_archive)
    p["import_archive"](g, "global", pack(after))
    # Explicit setup first; only then install exact/glob consumers on cold tags.
    g["create"](NAME + "-setup", """- route:
    from:
      uri: ignition-gateway-http:/setup
      steps:
        - to: ignition-script:component_benchmark.setup?project=global
""")
    wait("benchmark tag setup route", lambda: running(NAME + "-setup"))
    wait("benchmark project initialization", lambda: api("POST", "/system/integrations/" + NAME + "-setup/setup", {})["ready"], seconds=40)
    g["delete"](NAME + "-setup")
    g["create"](NAME, "component_benchmark.configure", language="JYTHON", project="global")
    wait("benchmark routes", lambda: running(NAME), seconds=40)
    installed = files(p["export_project"](g, "global"))
    # Ignition may materialize resource metadata during import. Compare cleanup
    # against its actual installed bytes, not our pre-import descriptor defaults.
    owned = {name: installed[name] for name in added}
    return owned, before[PAGE_CONFIG], installed[PAGE_CONFIG]


def running(name):
    detail = api("GET", g["MODULE"] + "/camel-route-details?name=" + name)
    if detail["state"] == "ERROR":
        raise RuntimeError("Benchmark startup failed: " + detail["startupError"])
    return detail["state"] == "RUNNING"


def background_ready():
    states = api("GET", g["MODULE"] + "/camel-routes")
    if "eip-default-mqtt" not in states:
        return True
    detail = api("GET", g["MODULE"] + "/camel-route-details?name=eip-default-mqtt")
    diag = detail["diagnostics"]
    assert diag["failed"] == 0 and diag["rejected"] == 0, "Background publisher failed during benchmark provisioning"
    return detail["state"] == "RUNNING" and diag["received"] - diag["processed"] < 4


def cleanup(owned):
    added, previous_page, installed_page = owned
    g["delete"](NAME)
    current = files(p["export_project"](g, "global"))
    assert current[PAGE_CONFIG] == installed_page, "Page configuration changed; retain backup and review cleanup manually"
    for name, content in added.items():
        assert current.get(name) == content, "Benchmark resource changed; refusing to discard edits: " + name
        del current[name]
    current[PAGE_CONFIG] = previous_page
    p["import_archive"](g, "global", pack(current))


def summary(name, size, nanos, elapsed, concurrency=1):
    values = sorted(nanos)
    def percentile(p):
        return values[max(0, math.ceil(len(values) * p) - 1)] / 1000
    return {"name": name, "paddingBytes": size, "samples": len(values), "concurrency": concurrency,
        "meanMicros": sum(values) / len(values) / 1000, "p50Micros": percentile(.50), "p95Micros": percentile(.95),
        "p99Micros": percentile(.99), "maxMicros": values[-1] / 1000,
        "closedLoopOpsPerSecond": len(values) * 1e9 / elapsed, "errors": 0, "nanos": nanos}


def http_benchmark(size, samples, warmup, concurrency):
    cookie = "; ".join(item.name + "=" + item.value for item in g["COOKIES"])
    csrf = api("GET", "/data/app/session")["csrfToken"]
    barrier = threading.Barrier(concurrency)
    headers = {"Content-Type": "application/json", "Cookie": cookie, "X-CSRF-Token": csrf}
    def worker(worker_id):
        connection = http.client.HTTPConnection("localhost", 9288, timeout=15)
        values, started_all = [], 0
        try:
            for index in range(warmup + samples):
                body = {"sequence": worker_id * (warmup + samples) + index, "padding": "x" * size}
                encoded = json.dumps(body, separators=(",", ":")).encode()
                if index == warmup:
                    barrier.wait(timeout=30)
                    started_all = time.perf_counter_ns()
                started = time.perf_counter_ns()
                connection.request("POST", ENDPOINT + "/echo", encoded, headers)
                response = connection.getresponse()
                raw = response.read()
                elapsed = time.perf_counter_ns() - started
                assert response.status == 200, (response.status, raw[:200])
                assert json.loads(raw) == body, "HTTP reply did not match its request"
                if index >= warmup:
                    values.append(elapsed)
            return values, started_all, time.perf_counter_ns()
        finally:
            connection.close()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(worker, range(concurrency)))
    nanos = [value for row in results for value in row[0]]
    elapsed = max(row[2] for row in results) - min(row[1] for row in results)
    return summary("ignition-gateway-http.authenticated-keepalive", size, nanos, elapsed, concurrency)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=300)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--padding-bytes", type=int, nargs="+", default=[128, 4096])
    parser.add_argument("--http-concurrency", type=int, nargs="+", default=[1, 4])
    parser.add_argument("--perspective", action="store_true", help="Require a real headless Perspective page; fail rather than stub it")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    assert 20 <= args.samples <= 5000 and 1 <= args.warmup <= 1000
    assert 1 <= len(args.padding_bytes) <= 4 and all(0 <= value <= 32768 for value in args.padding_bytes)
    assert all(1 <= value <= 16 for value in args.http_concurrency)
    assert g["BASE"] == "http://localhost:9288"
    output = args.output_dir or ROOT / "local" / ("component-benchmark-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=False)
    wait("local Gateway login", g["bootstrap"])
    owned = provision(output)
    browser = None
    try:
        wait("initial background tag/MQTT snapshot to drain", background_ready, seconds=120)
        session = ""
        if args.perspective:
            browser = subprocess.Popen(["node", str(ROOT / "scripts/camel-component-benchmark-browser.cjs")],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=os.environ.copy())
            line = browser.stdout.readline()
            assert line, "Perspective page unavailable: " + browser.stderr.read()
            session = json.loads(line)["sessionId"]
            uuid.UUID(session)
        run_id = uuid.uuid4().hex
        config = {"samples": args.samples, "warmup": args.warmup, "paddingBytes": args.padding_bytes, "sessionId": session, "runId": run_id}
        assert api("POST", ENDPOINT + "/run", config)["accepted"]
        def finished():
            value = api("POST", ENDPOINT + "/results", {})
            return value if value.get("runId") == run_id and value["state"] in ["COMPLETE", "FAILED"] else False
        report = wait("real component measurements", finished, seconds=300)
        (output / "gateway.json").write_text(json.dumps(report, indent=2))
        assert report["state"] == "COMPLETE", report
        for row in report["rows"]:
            row["nanos"] = api("POST", ENDPOINT + "/samples", {"index": row["sampleIndex"]})["nanos"]
        report["http"] = [http_benchmark(size, args.samples, args.warmup, concurrency)
            for size in args.padding_bytes for concurrency in args.http_concurrency]
        report["routeDetails"] = api("GET", g["MODULE"] + "/camel-route-details?name=" + NAME)
        diag = report["routeDetails"]["diagnostics"]
        assert all(diag[key] == 0 for key in ["failed", "rejected", "httpFailed", "httpRejected"]), diag
        report["environment"] = {"gateway": g["BASE"], "backgroundFactoryAndMqtt": "left running", "config": config,
            "timing": "Gateway System.nanoTime for native cases; client perf_counter_ns for HTTP; clocks never subtracted across machines",
            "perspectiveReceipt": "targeted Gateway-side Perspective component message handler, not DOM render or browser network roundtrip"}
        (output / "results.json").write_text(json.dumps(report, indent=2))
        for row in report["rows"] + report["http"]:
            print("%-62s %5d bytes c=%d p50=%9.2f us p95=%9.2f us p99=%9.2f us errors=%d" %
                (row["name"], row["paddingBytes"], row.get("concurrency", 1), row["p50Micros"], row["p95Micros"], row["p99Micros"], row["errors"]))
        print("Results and pre-change project export: " + str(output))
    except Exception as error:
        (output / "failure.json").write_text(json.dumps({"state": "FAILED", "error": str(error)}, indent=2))
        raise
    finally:
        if browser:
            browser.communicate("stop\n", timeout=15)
        cleanup(owned)


if __name__ == "__main__":
    main()
