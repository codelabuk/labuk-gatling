"""Import a LoadRunner VuGen script as a new test-plan suite - the same converter as `perf convert-lr`.

The dashboard image carries Java and the perf distribution (copied from the runner image), so the conversion is
exactly the one developers get from the CLI. Flow: the uploaded script (zip or folder files) is written to a
temporary folder, `perf convert-lr` runs on it, and its output - plan, API catalog, data, conversion report - is
stored as a new suite. Nothing is stored if the conversion fails.
"""
import io
import os
import posixpath
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

import config
import plan_store
from plan_store import PlanError

# files the converter reads (scripts, parameters, data); everything else in a VuGen folder - recording snapshots,
# logs, result folders, binaries - is skipped
SCRIPT_FILES = {".c", ".h", ".prm", ".dat", ".usr", ".cfg", ".csv", ".txt"}
NAME = re.compile(r"[^A-Za-z0-9_-]")


def _safe_parts(name):
    parts = [p for p in name.replace("\\", "/").split("/") if p]
    if name.startswith(("/", "\\")) or ".." in parts or any(":" in p for p in parts):
        raise PlanError(f"'{name}' points outside the script folder - nothing was imported")
    return parts


def _collect(files):
    """[(relative path, bytes)] of the script files in the upload; zips are unpacked. Returns (files, skipped count)."""
    out, skipped = [], 0
    for name, data in files:
        if name.lower().endswith(".zip"):
            try:
                zf = zipfile.ZipFile(io.BytesIO(data))
            except zipfile.BadZipFile:
                raise PlanError(f"{name} is not a valid zip file")
            entries = [(i.filename, i) for i in zf.infolist() if not i.is_dir()]
            for entry, info in entries:
                parts = _safe_parts(entry)
                if posixpath.splitext(entry)[1].lower() not in SCRIPT_FILES or any(p.startswith((".", "__MACOSX")) for p in parts):
                    skipped += 1
                    continue
                if info.file_size > config.PLANS_MAX_FILE_BYTES:
                    raise PlanError(f"{entry}: larger than {config.PLANS_MAX_FILE_BYTES // (1024 * 1024)} MB")
                out.append(("/".join(parts), zf.read(info)))
        else:
            parts = _safe_parts(name)
            if posixpath.splitext(name)[1].lower() not in SCRIPT_FILES:
                skipped += 1
                continue
            out.append(("/".join(parts), data))
    return out, skipped


def _script_root(paths):
    """The folder holding the script (where Action*.c / vuser_init.c live), relative to the upload."""
    roots = {posixpath.dirname(p) for p in paths
             if re.fullmatch(r"(action.*|vuser_init|vuser_end)\.c", posixpath.basename(p).lower())}
    if not roots:
        raise PlanError("no VuGen script found - expected Action.c (and vuser_init.c / vuser_end.c) in the upload")
    if len(roots) > 1:
        raise PlanError(f"more than one script in the upload ({', '.join(sorted(r or '(top)' for r in roots))}) - import one at a time")
    return roots.pop()


def _script_name(paths, root, suite):
    """Name for the plan file: the script's .usr or .prm name, else the suite name."""
    for ext in (".usr", ".prm"):
        for p in paths:
            if posixpath.dirname(p) == root and p.lower().endswith(ext):
                return NAME.sub("-", posixpath.splitext(posixpath.basename(p))[0]) or suite
    return NAME.sub("-", suite)


def _convert(script_dir, out_dir, iterations):
    cmd = [config.PERF_JAVA, "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Dperf.relaunched=true",
           f"-Dlogback.configurationFile={Path(config.PERF_HOME, 'conf', 'logback.xml')}",
           "-cp", str(Path(config.PERF_HOME, "lib")) + os.sep + "*",
           "org.codelabuk.perf.runner.Perf", "convert-lr", str(script_dir), "--out", str(out_dir),
           "--iterations", str(iterations)]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=config.CONVERT_TIMEOUT_SECONDS)
    except FileNotFoundError:
        raise PlanError(f"LoadRunner import is not available here: Java not found at {config.PERF_JAVA} "
                        "(the dashboard image includes it; for local development set PERF_JAVA and PERF_HOME)")
    except subprocess.TimeoutExpired:
        raise PlanError(f"conversion took longer than {config.CONVERT_TIMEOUT_SECONDS} s - stopped")
    if r.returncode != 0:
        msg = (r.stderr or r.stdout).strip().splitlines()
        raise PlanError("conversion failed: " + (msg[-1] if msg else f"exit code {r.returncode}"))
    m = re.search(r"converted (\d+) request", r.stdout)
    return int(m.group(1)) if m else None


def import_script(suite, files, iterations=1):
    """Converts an uploaded VuGen script and stores the result as a new suite. Returns what was created."""
    suite = plan_store.suite_name(suite)
    if plan_store.exists(suite):
        raise PlanError(f"suite {suite} already exists - choose a new name")
    if not 1 <= iterations <= 1000:
        raise PlanError("iterations must be between 1 and 1000")
    script, skipped = _collect(files)
    if not script:
        raise PlanError("no script files in the upload (.c, .h, .prm, .dat, .usr)")
    root = _script_root([p for p, _ in script])
    name = _script_name([p for p, _ in script], root, suite)

    work = Path(tempfile.mkdtemp(prefix="lr-import-"))
    try:
        src = work / name
        for path, data in script:
            rel = path[len(root):].lstrip("/") if root else path
            target = (src / rel).resolve()
            if not str(target).startswith(str(src.resolve())):
                raise PlanError(f"'{path}' points outside the script folder - nothing was imported")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        out = work / "out"
        requests = _convert(src, out, iterations)
        produced = sorted(p for p in out.rglob("*") if p.is_file())
        report_file = out / f"{name}-conversion.txt"
        notes = report_file.read_text(encoding="utf-8").strip().splitlines() if report_file.exists() else []
        stored = plan_store.upload(suite, [(p.relative_to(out).as_posix(), p.read_bytes()) for p in produced], unzip=False)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return {
        "suite": suite,
        "plan": f"{name}.yaml",
        "report": f"{name}-conversion.txt" if f"{name}-conversion.txt" in stored else None,
        "requests": requests,
        "notes": [n[2:].strip() for n in notes if n.startswith("- ")],
        "files": stored,
        "skipped": skipped,
    }
