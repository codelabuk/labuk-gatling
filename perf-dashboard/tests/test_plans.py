"""Saved test plans (suites in S3) and deleting run results - against an in-memory MinIO."""
import io
import zipfile

import pytest

import config
import kube
import storage
from app import create_app
from fake_s3 import FakeMinio

PLAN = b"""name: sr
baseUrl: ${BASE_URL:http://localhost:18080}
include: [ ../apis/sr.yaml ]
scenarios:
  - name: s
    steps: [ { api: ping } ]
    load: { profile: [ { atOnceUsers: 1 } ] }
"""


def _zip(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    return buf.getvalue()


@pytest.fixture
def s3(monkeypatch):
    fake = FakeMinio()
    monkeypatch.setattr(storage, "_client", fake)
    monkeypatch.setattr(storage, "_cache", {})
    monkeypatch.setattr(config, "RESULTS_URL", "s3://perf-results/runs")
    monkeypatch.setattr(config, "PLANS_URL", "s3://perf-results/test-plans")
    monkeypatch.setattr(config, "WATCH_NAMESPACES", ["perf"])
    return fake


@pytest.fixture
def client(s3, monkeypatch):
    state = {"loadtests": [], "created": None, "deleted": None}
    monkeypatch.setattr(kube, "list_loadtests", lambda nss: state["loadtests"])
    monkeypatch.setattr(kube, "get_loadtest", lambda ns, name: next(lt for lt in state["loadtests"] if lt["metadata"]["name"] == name))
    monkeypatch.setattr(kube, "create_loadtest", lambda ns, body: state.update(created=body) or body)
    monkeypatch.setattr(kube, "delete_loadtest", lambda ns, name: state.update(deleted=name))
    c = create_app().test_client()
    c.state = state
    return c


def _upload(client, suite, files, **form):
    data = {"files": [(io.BytesIO(d), name) for name, d in files.items()], **form}
    return client.post(f"/api/plans/{suite}/upload", data=data, content_type="multipart/form-data")


# ------------------------------------------------------------------ suites and files

def test_new_suite_gets_a_starter_plan(client, s3):
    r = client.post("/api/plans", json={"name": "orders-api"})
    assert r.status_code == 201
    assert s3.keys() == ["test-plans/orders-api/apis/example.yaml", "test-plans/orders-api/plans/example.yaml"]
    files = {f["path"]: f for f in r.get_json()["files"]}
    assert files["plans/example.yaml"]["plan"] and not files["apis/example.yaml"]["plan"]   # only plans have scenarios
    assert client.post("/api/plans", json={"name": "orders-api"}).status_code == 400          # already exists
    assert client.get("/api/plans").get_json()[0]["name"] == "orders-api"


def test_upload_files_keeps_folders_and_edit_round_trip(client, s3):
    r = _upload(client, "sr-flow", {"plans/sr.yaml": PLAN, "apis/sr.yaml": b"apis: {}\n", "data/users.csv": b"username\nu1\n"})
    assert r.status_code == 200 and sorted(r.get_json()["uploaded"]) == ["apis/sr.yaml", "data/users.csv", "plans/sr.yaml"]
    f = client.get("/api/plans/sr-flow/file?path=plans/sr.yaml").get_json()
    assert f["editable"] and f["content"] == PLAN.decode()
    assert client.put("/api/plans/sr-flow/file?path=plans/sr.yaml", json={"content": "name: x\nscenarios: []\n"}).status_code == 200
    assert s3.objects[("perf-results", "test-plans/sr-flow/plans/sr.yaml")] == b"name: x\nscenarios: []\n"
    assert client.delete("/api/plans/sr-flow/file?path=data/users.csv").status_code == 200
    assert "test-plans/sr-flow/data/users.csv" not in s3.keys()


def test_zip_of_a_folder_is_unpacked_without_its_top_folder(client, s3):
    z = _zip({"sr-flow/plans/sr.yaml": PLAN, "sr-flow/apis/sr.yaml": "apis: {}\n", "__MACOSX/x": "junk", "sr-flow/.git/config": "x"})
    assert _upload(client, "sr-flow", {"sr-flow.zip": z}).status_code == 200
    assert s3.keys() == ["test-plans/sr-flow/apis/sr.yaml", "test-plans/sr-flow/plans/sr.yaml"]
    # and back out as a zip
    out = zipfile.ZipFile(io.BytesIO(client.get("/api/plans/sr-flow/download").data))
    assert sorted(out.namelist()) == ["sr-flow/apis/sr.yaml", "sr-flow/plans/sr.yaml"]


def test_invalid_yaml_is_refused_and_nothing_of_the_upload_is_written(client, s3):
    r = _upload(client, "sr-flow", {"plans/good.yaml": PLAN, "plans/bad.yaml": b"scenarios: [ unclosed\n"})
    assert r.status_code == 400 and "plans/bad.yaml: invalid YAML" in r.get_json()["error"]
    assert s3.keys() == []
    r = client.put("/api/plans/sr-flow/file?path=plans/x.yaml", json={"content": "a: [\n"})
    assert r.status_code == 400 and "invalid YAML" in r.get_json()["error"]


@pytest.mark.parametrize("path", ["../escape.yaml", "plans/../../x.yaml", "/abs.yaml", ".hidden", "plans/.env", "a//b", ""])
def test_paths_cannot_leave_the_suite(client, s3, path):
    r = client.put(f"/api/plans/sr-flow/file?path={path}", json={"content": "x: 1\n"})
    assert r.status_code == 400
    assert s3.keys() == []


def test_zip_entries_cannot_leave_the_suite(client, s3):
    r = _upload(client, "sr-flow", {"evil.zip": _zip({"plans/ok.yaml": "a: 1\n", "../../runs/x/report/index.html": "<h1>"})})
    assert r.status_code == 400 and s3.keys() == []


def test_bad_suite_names(client):
    for name in ["../runs", "-x", "a b", "x" * 70]:
        assert client.post("/api/plans", json={"name": name}).status_code in (400, 404, 405)


def test_delete_suite(client, s3):
    _upload(client, "a", {"plans/p.yaml": PLAN})
    _upload(client, "ab", {"plans/p.yaml": PLAN})
    assert client.delete("/api/plans/a").get_json()["objects"] == 1
    assert s3.keys() == ["test-plans/ab/plans/p.yaml"]      # 'a/' prefix only - not 'ab'
    assert client.delete("/api/plans/a").status_code == 400  # gone


# ------------------------------------------------------------------ runs from saved plans

def test_run_from_a_saved_suite_points_the_operator_at_it(client):
    _upload(client, "sr-flow", {"plans/sr.yaml": PLAN})
    r = client.post("/api/loadtests", json={"plan": {"suite": "sr-flow", "file": "plans/sr.yaml"}, "workers": 3})
    assert r.status_code == 201
    plan = client.state["created"]["spec"]["plan"]
    assert plan == {"file": "plans/sr.yaml", "s3": "s3://perf-results/test-plans/sr-flow"}


def test_run_from_a_saved_suite_needs_the_plan_file(client):
    _upload(client, "sr-flow", {"plans/sr.yaml": PLAN})
    r = client.post("/api/loadtests", json={"plan": {"suite": "sr-flow", "file": "plans/missing.yaml"}})
    assert r.status_code == 400 and "not in the saved suite" in r.get_json()["error"]
    r = client.post("/api/loadtests", json={"plan": {"suite": "sr-flow", "file": "plans/sr.yaml", "configMap": "c"}})
    assert r.status_code == 400 and "exactly one test source" in r.get_json()["error"]


# ------------------------------------------------------------------ deleting results

def _run(s3, folder, files=("report/summary.json", "logs/worker-0.json", "tests/plans/sr.yaml")):
    for f in files:
        s3.objects[("perf-results", f"runs/{folder}/{f}")] = b"{}"


def _lt(name, phase, folder):
    return {"metadata": {"name": name, "namespace": "perf"},
            "status": {"phase": phase, "runDir": f"s3://perf-results/runs/{folder}"}}


def test_delete_run_results_from_history(client, s3):
    _run(s3, "sr-1-aaaa1111")
    _run(s3, "sr-1-aaaa11112")          # a longer name with the same start must survive
    r = client.delete("/api/runs/sr-1-aaaa1111")
    assert r.status_code == 200 and r.get_json()["objects"] == 3
    assert all(k.startswith("runs/sr-1-aaaa11112/") for k in s3.keys())
    assert client.delete("/api/runs/sr-1-aaaa1111").status_code == 404


def test_results_of_an_active_run_are_not_deleted(client, s3):
    _run(s3, "sr-2-bbbb2222")
    client.state["loadtests"] = [_lt("sr-2", "Running", "sr-2-bbbb2222")]
    assert client.delete("/api/runs/sr-2-bbbb2222").status_code == 409
    assert client.delete("/api/loadtests/perf/sr-2?results=true").status_code == 409
    assert len(s3.keys()) == 3 and client.state["deleted"] is None


def test_delete_loadtest_with_its_results(client, s3):
    _run(s3, "sr-3-cccc3333")
    client.state["loadtests"] = [_lt("sr-3", "Completed", "sr-3-cccc3333")]
    r = client.delete("/api/loadtests/perf/sr-3?results=true")
    assert r.status_code == 200 and r.get_json()["resultObjects"] == 3
    assert s3.keys() == [] and client.state["deleted"] == "sr-3"


def test_delete_loadtest_alone_keeps_results(client, s3):
    _run(s3, "sr-4-dddd4444")
    client.state["loadtests"] = [_lt("sr-4", "Completed", "sr-4-dddd4444")]
    assert client.delete("/api/loadtests/perf/sr-4").status_code == 200
    assert len(s3.keys()) == 3 and client.state["deleted"] == "sr-4"


@pytest.mark.parametrize("folder", ["..", "UPPER", "a_b", "x.y"])
def test_run_folder_names_are_checked(client, s3, folder):
    assert client.delete(f"/api/runs/{folder}").status_code in (404, 405)
