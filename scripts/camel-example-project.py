"""Non-destructive shared project installation for the isolated Camel demos."""
import io
import json
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

PROJECT = "CamelExamples"
LIBRARIES = ("tags", "unified_readings", "work_orders", "control_bus",
             "work_order_mqtt", "idempotent_consumer")
ROOT = Path(__file__).resolve().parents[1]


def merge_libraries(archive, libraries):
    """Add missing libraries; never replace existing code, metadata or settings."""
    files = {}
    if archive is not None:
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            files = {entry.filename: source.read(entry) for entry in source.infolist()}
    else:
        files["project.json"] = json.dumps({"title": PROJECT, "enabled": True,
                                             "inheritable": False, "parent": ""}).encode()
    for name, entries in libraries.items():
        prefix = "ignition/script-python/" + name + "/"
        if any(path.startswith(prefix) for path in files):
            for path, value in entries.items():
                if files.get(prefix + path) != value:
                    raise ValueError("Existing project library conflicts with " + name + "; refusing to overwrite")
            continue
        files.update({prefix + path: value for path, value in entries.items()})
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as target:
        for path, value in files.items():
            target.writestr(path, value)
    return output.getvalue()


def export_project(gateway, name):
    try:
        with gateway["HTTP"].open(gateway["BASE"] + "/data/api/v1/projects/export/" + name, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise


def import_archive(gateway, name, archive):
    token = gateway["api"]("GET", "/data/app/session")["csrfToken"]
    request = urllib.request.Request(
        gateway["BASE"] + "/data/api/v1/projects/import/" + name + "?overwrite=true",
        method="POST", data=archive,
        headers={"Content-Type": "application/zip", "X-CSRF-Token": token})
    with gateway["HTTP"].open(request, timeout=30) as response:
        result = json.load(response)
        assert result.get("success"), result


def ensure_project(gateway, required=LIBRARIES):
    unknown = set(required) - set(LIBRARIES)
    if unknown:
        raise ValueError("Unknown example libraries: " + ", ".join(sorted(unknown)))
    archive = export_project(gateway, PROJECT)
    paths = set()
    if archive is not None:
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            paths = set(source.namelist())
    libraries = {}
    for name in required:
        prefix = "ignition/script-python/" + name + "/"
        if any(path.startswith(prefix) for path in paths):
            continue
        libraries[name] = {
            "resource.json": json.dumps({"scope": "A", "version": 1, "restricted": False,
                                          "overridable": True, "files": ["code.py"],
                                          "attributes": {"hintScope": 1}}).encode(),
            "code.py": (ROOT / "examples/camel/project" / (name + ".py")).read_bytes(),
        }
    if libraries or archive is None:
        import_archive(gateway, PROJECT, merge_libraries(archive, libraries))
