"""Import LoadRunner script: runs the real `perf convert-lr` (from the local build) on the sample VuGen script."""
import io
import pathlib
import shutil
import subprocess
import types
import zipfile

import pytest

import config
import lr_import
from test_plans import client, s3  # noqa: F401  (fixtures)

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / "gatling-runner/src/test/resources/lr/SR_Flow"
PERF_HOME = REPO / "gatling-runner/target/perf-1.0-SNAPSHOT/perf-1.0-SNAPSHOT"
JAVA = shutil.which("java")
needs_converter = pytest.mark.skipif(not (JAVA and (PERF_HOME / "lib").is_dir()),
                                     reason="needs java and the perf distribution (mvn -pl gatling-runner -am package)")


@pytest.fixture(autouse=True)
def converter(monkeypatch):
    monkeypatch.setattr(config, "PERF_JAVA", JAVA or "java")
    monkeypatch.setattr(config, "PERF_HOME", str(PERF_HOME))


def _zip(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    return buf.getvalue()


def _script_zip(top="SR_Flow", extra=None):
    files = {f"{top}/{p.name}" if top else p.name: p.read_bytes() for p in SCRIPT.iterdir() if p.is_file()}
    files.update(extra or {})
    return _zip(files)


def _post(client, suite, files, iterations=None):
    data = {"suite": suite, "files": [(io.BytesIO(d), name) for name, d in files.items()]}
    if iterations is not None:
        data["iterations"] = str(iterations)
    return client.post("/api/plans/import-lr", data=data, content_type="multipart/form-data")


@needs_converter
def test_import_zip_creates_a_runnable_suite(client, s3):
    z = _script_zip(extra={"SR_Flow/data/snapshot_1.inf": b"recording", "SR_Flow/Action.c.bak": b"old"})
    r = _post(client, "lr-sr-flow", {"SR_Flow.zip": z})
    assert r.status_code == 201, r.get_json()
    body = r.get_json()
    assert body["plan"] == "SR_Flow.yaml" and body["report"] == "SR_Flow-conversion.txt"
    assert body["requests"] == 4 and len(body["notes"]) == 5 and body["skipped"] == 2   # snapshot + .bak skipped
    assert any("lr_rendezvous" in n for n in body["notes"])
    assert s3.keys() == ["test-plans/lr-sr-flow/SR_Flow-conversion.txt", "test-plans/lr-sr-flow/SR_Flow.yaml",
                         "test-plans/lr-sr-flow/apis/SR_Flow.yaml", "test-plans/lr-sr-flow/data/users.dat"]
    files = {f["path"]: f for f in client.get("/api/plans/lr-sr-flow/files").get_json()["files"]}
    assert files["SR_Flow.yaml"]["plan"]           # offered on New run
    # and it can be started from the dashboard right away
    run = client.post("/api/loadtests", json={"plan": {"suite": "lr-sr-flow", "file": "SR_Flow.yaml"}})
    assert run.status_code == 201


@needs_converter
def test_import_folder_files_and_iterations(client, s3):
    files = {f"SR_Flow/{p.name}": p.read_bytes() for p in SCRIPT.iterdir() if p.is_file()}
    r = _post(client, "lr-folder", files, iterations=3)
    assert r.status_code == 201, r.get_json()
    plan = s3.objects[("perf-results", "test-plans/lr-folder/SR_Flow.yaml")].decode()
    assert "times: 3" in plan                      # Action repeated --iterations times


def test_existing_suite_is_not_overwritten(client, s3):
    s3.objects[("perf-results", "test-plans/taken/plans/p.yaml")] = b"x: 1\n"
    r = _post(client, "taken", {"SR_Flow.zip": _script_zip()})
    assert r.status_code == 400 and "already exists" in r.get_json()["error"]


@pytest.mark.parametrize("files,error", [
    ({"x.zip": _zip({"readme.txt": "no script"})}, "no VuGen script"),
    ({"x.zip": _zip({"a/Action.c": "x", "b/Action.c": "y"})}, "more than one script"),
    ({"x.zip": _zip({"../evil/Action.c": "x"})}, "outside the script folder"),
    ({"x.zip": b"not a zip"}, "not a valid zip"),
    ({"photo.png": b"\x89PNG"}, "no script files"),
])
def test_bad_uploads_store_nothing(client, s3, files, error):
    r = _post(client, "lr-bad", files)
    assert r.status_code == 400 and error in r.get_json()["error"], r.get_json()
    assert s3.keys() == []


def test_a_failed_conversion_stores_nothing(client, s3, monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: types.SimpleNamespace(returncode=1, stdout="", stderr="perf convert-lr: boom"))
    r = _post(client, "lr-fail", {"SR_Flow.zip": _script_zip()})
    assert r.status_code == 400 and "conversion failed: perf convert-lr: boom" in r.get_json()["error"]
    assert s3.keys() == []


def test_missing_java_is_a_clear_message(client, s3, monkeypatch):
    monkeypatch.setattr(config, "PERF_JAVA", "/nowhere/java")
    r = _post(client, "lr-nojava", {"SR_Flow.zip": _script_zip()})
    assert r.status_code == 400 and "Java not found" in r.get_json()["error"]


def test_script_name_falls_back_to_the_suite():
    assert lr_import._script_name(["Action.c", "vuser_init.c"], "", "my suite") == "my-suite"
    assert lr_import._script_name(["x/Checkout.usr", "x/Action.c"], "x", "s") == "Checkout"
