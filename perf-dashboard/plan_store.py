"""Test plans kept in S3 / MinIO and edited from the dashboard.

Layout: one folder ("suite") per test set under PLANS_URL, organised like a test repository:

    s3://perf-results/test-plans/sr-flow/plans/sr-email-status.yaml
                                        /apis/sr.yaml
                                        /data/users.csv

Plans keep their relative paths (include:, data files), so a suite works the same as a Git checkout. A run points the
operator at the suite (spec.plan.s3); its validate pod stores a copy in the run folder, which all later pods use.
"""
import io
import posixpath
import re
import zipfile
from datetime import timezone

import yaml
from minio.error import S3Error

import config
import storage

SUITE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")
SEGMENT = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._ -]{0,127}$")     # no '.', '..', hidden or empty names
TEXT = {".yaml", ".yml", ".json", ".csv", ".txt", ".md", ".xml", ".sql", ".properties", ".dat", ".tsv", ".c", ".h", ".prm"}
EDITABLE_MAX = 2 * 1024 * 1024
CONTENT_TYPES = {".yaml": "application/yaml", ".yml": "application/yaml", ".json": "application/json", ".csv": "text/csv"}

STARTER = {
    "plans/example.yaml": """\
# Starter plan: one API under a steady load. Replace the API, the load and the SLAs with your own.
# ${NAME:default} values come from the run's Variables (e.g. BASE_URL=https://sit.api.example.com).
name: example
baseUrl: ${BASE_URL:http://localhost:18080}
include: [ ../apis/example.yaml ]

scenarios:
  - name: ping
    steps:
      - api: ping
    load:
      profile:
        - constantUsersPerSec: { rate: 5, during: 30s }

sla:
  - global.failedRequests.percent < 1
  - api.ping.responseTime.p95 < 500ms
""",
    "apis/example.yaml": """\
# API catalog: each API is defined once and used by any plan of this suite.
apis:
  ping:
    request: GET /ping
    checks:
      - status: 200
""",
}


class PlanError(ValueError):
    """A request the store refuses (bad name, missing file, invalid YAML) - shown to the user as is."""


def suite_name(name):
    name = (name or "").strip()
    if not SUITE.match(name):
        raise PlanError("suite name: letters, digits, '.', '_' or '-' (at most 63), starting with a letter or digit")
    return name


def file_path(path):
    """A safe relative path inside a suite: plain folder and file names, no '..', no hidden files."""
    path = (path or "").strip().replace("\\", "/")
    parts = path.rstrip("/").split("/") if path else []
    if path.startswith("/") or not parts or len(path) > 400 or not all(SEGMENT.match(p) for p in parts):
        raise PlanError(f"invalid file path '{path}': use folders and names of letters, digits, '.', '_', '-' and spaces")
    return "/".join(parts)


def _root():
    bucket, prefix = config.plans_bucket_and_prefix()
    return bucket, f"{prefix}/" if prefix else ""


def suite_url(suite):
    """s3:// folder of a suite - what a LoadTest's spec.plan.s3 points to."""
    bucket, root = _root()
    return f"s3://{bucket}/{root}{suite_name(suite)}"


def _key(suite, path):
    bucket, root = _root()
    return bucket, f"{root}{suite_name(suite)}/{file_path(path)}"


def _iso(dt):
    return dt.astimezone(timezone.utc).isoformat() if dt else None


def _objects(prefix):
    bucket, _ = _root()
    return [o for o in storage._minio().list_objects(bucket, prefix=prefix, recursive=True) if not o.object_name.endswith("/")]


def list_suites():
    _, root = _root()
    suites = {}
    for o in _objects(root):
        rel = o.object_name[len(root):]
        if "/" not in rel:
            continue
        name = rel.split("/", 1)[0]
        s = suites.setdefault(name, {"name": name, "files": 0, "size": 0, "modified": None})
        s["files"] += 1
        s["size"] += o.size or 0
        m = _iso(o.last_modified)
        if m and (s["modified"] is None or m > s["modified"]):
            s["modified"] = m
    return sorted(suites.values(), key=lambda s: s["name"].lower())


def exists(suite):
    _, root = _root()
    return bool(_objects(f"{root}{suite_name(suite)}/"))


def _is_plan(data):
    """A plan is a YAML mapping with scenarios (API catalogs and other YAML are not)."""
    try:
        doc = yaml.safe_load(data.decode("utf-8"))
    except (UnicodeDecodeError, yaml.YAMLError):
        return False
    return isinstance(doc, dict) and "scenarios" in doc


def list_files(suite):
    _, root = _root()
    base = f"{root}{suite_name(suite)}/"
    files = []
    for o in _objects(base):
        path = o.object_name[len(base):]
        ext = posixpath.splitext(path)[1].lower()
        plan = ext in (".yaml", ".yml") and (o.size or 0) <= 512 * 1024 and _is_plan(_read(*_key(suite, path)) or b"")
        files.append({"path": path, "size": o.size or 0, "modified": _iso(o.last_modified),
                      "text": ext in TEXT, "plan": plan})
    return sorted(files, key=lambda f: f["path"].lower())


def _read(bucket, key):
    try:
        resp = storage._minio().get_object(bucket, key)
    except S3Error as e:
        if e.code in ("NoSuchKey", "NoSuchBucket"):
            return None
        raise
    try:
        return resp.read()
    finally:
        resp.close()
        resp.release_conn()


def read_file(suite, path):
    data = _read(*_key(suite, path))
    if data is None:
        raise PlanError(f"{path} not found in suite {suite}")
    return data


def file_exists(suite, path):
    bucket, key = _key(suite, path)
    try:
        storage._minio().stat_object(bucket, key)
        return True
    except S3Error as e:
        if e.code in ("NoSuchKey", "NoSuchBucket", "NoSuchObject"):
            return False
        raise


def check(path, data):
    """Problem that would break a run, or None. Only syntax: the full plan check runs in the run's validate step."""
    if len(data) > config.PLANS_MAX_FILE_BYTES:
        return f"larger than {config.PLANS_MAX_FILE_BYTES // (1024 * 1024)} MB"
    if posixpath.splitext(path)[1].lower() in (".yaml", ".yml"):
        try:
            yaml.safe_load(data.decode("utf-8"))
        except UnicodeDecodeError:
            return "not UTF-8 text"
        except yaml.YAMLError as e:
            return f"invalid YAML - {e}"
    return None


def _put(suite, path, data):
    bucket, key = _key(suite, path)
    ctype = CONTENT_TYPES.get(posixpath.splitext(path)[1].lower(), "application/octet-stream")
    storage._minio().put_object(bucket, key, io.BytesIO(data), len(data), content_type=ctype)


def write_file(suite, path, data):
    path = file_path(path)
    problem = check(path, data)
    if problem:
        raise PlanError(f"{path}: {problem}")
    _put(suite, path, data)
    return path


def delete_file(suite, path):
    bucket, key = _key(suite, path)
    if not file_exists(suite, path):
        raise PlanError(f"{path} not found in suite {suite}")
    storage._minio().remove_object(bucket, key)


def delete_suite(suite):
    bucket, root = _root()
    return storage.delete_prefix(bucket, f"{root}{suite_name(suite)}/")


def create_suite(suite):
    suite = suite_name(suite)
    if exists(suite):
        raise PlanError(f"suite {suite} already exists")
    for path, text in STARTER.items():
        _put(suite, path, text.encode("utf-8"))
    return suite


def _zip_entries(data):
    """(path, bytes) of a zip's files; a single top folder (zip of a folder) is removed; OS junk is skipped."""
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise PlanError("not a valid zip file")
    entries = []
    for info in zf.infolist():
        name = info.filename.replace("\\", "/")
        if name.startswith("/") or ".." in name.split("/"):
            raise PlanError(f"zip entry '{name}' points outside the suite - nothing was uploaded")
        if info.is_dir() or name.startswith("__MACOSX/") or any(p.startswith(".") for p in name.split("/") if p):
            continue      # folders, macOS metadata, hidden files (.git, .DS_Store)
        if info.file_size > config.PLANS_MAX_FILE_BYTES:
            raise PlanError(f"{name}: larger than {config.PLANS_MAX_FILE_BYTES // (1024 * 1024)} MB")
        entries.append((name, info))
    tops = {n.split("/", 1)[0] for n, _ in entries}
    strip = len(tops) == 1 and all("/" in n for n, _ in entries)
    return [(n.split("/", 1)[1] if strip else n, zf.read(info)) for n, info in entries]


def upload(suite, files, folder="", unzip=True):
    """files: [(relative path, bytes)]. Zips are unpacked (keeping folders). Everything is checked before anything
    is written, so a bad file in an upload changes nothing. Returns the stored paths."""
    suite = suite_name(suite)
    folder = file_path(folder) + "/" if (folder or "").strip("/ ") else ""
    pending = []
    for name, data in files:
        if unzip and name.lower().endswith(".zip"):
            pending += [(folder + p, d) for p, d in _zip_entries(data)]
        else:
            pending.append((folder + name, data))
    if not pending:
        raise PlanError("nothing to upload")
    checked = []
    for name, data in pending:
        path = file_path(name)
        problem = check(path, data)
        if problem:
            raise PlanError(f"{path}: {problem} - nothing was uploaded")
        checked.append((path, data))
    for path, data in checked:
        _put(suite, path, data)
    return [p for p, _ in checked]


def download_zip(suite):
    _, root = _root()
    base = f"{root}{suite_name(suite)}/"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for o in _objects(base):
            zf.writestr(f"{suite}/{o.object_name[len(base):]}", _read(_root()[0], o.object_name) or b"")
    return buf.getvalue()
