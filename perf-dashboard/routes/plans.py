"""Test plans in S3 / MinIO: suites (folders) of plans, API catalogs and data, managed from the dashboard."""
from flask import Blueprint, Response, jsonify, request

import plan_store
from plan_store import PlanError

bp = Blueprint("plans", __name__, url_prefix="/api/plans")


@bp.errorhandler(PlanError)
def _plan_error(e):
    return jsonify(error=str(e)), 400


@bp.get("")
def list_suites():
    return jsonify(plan_store.list_suites())


@bp.post("")
def create_suite():
    name = plan_store.create_suite((request.get_json(force=True) or {}).get("name"))
    return jsonify({"name": name, "url": plan_store.suite_url(name), "files": plan_store.list_files(name)}), 201


@bp.delete("/<suite>")
def delete_suite(suite):
    n = plan_store.delete_suite(suite)
    if n == 0:
        raise PlanError(f"suite {suite} not found")
    return {"deleted": suite, "objects": n}


@bp.get("/<suite>/files")
def list_files(suite):
    if not plan_store.exists(suite):
        return jsonify(error=f"suite {suite} not found"), 404
    return {"name": suite, "url": plan_store.suite_url(suite), "files": plan_store.list_files(suite)}


@bp.get("/<suite>/file")
def read_file(suite):
    path = plan_store.file_path(request.args.get("path"))
    data = plan_store.read_file(suite, path)
    try:
        text = data.decode("utf-8") if len(data) <= plan_store.EDITABLE_MAX else None
    except UnicodeDecodeError:
        text = None
    return {"path": path, "size": len(data), "content": text, "editable": text is not None}


@bp.put("/<suite>/file")
def write_file(suite):
    body = request.get_json(force=True) or {}
    content = body.get("content")
    if not isinstance(content, str):
        raise PlanError("content (text) is required")
    path = plan_store.write_file(suite, request.args.get("path") or body.get("path"), content.encode("utf-8"))
    return {"saved": path}


@bp.delete("/<suite>/file")
def delete_file(suite):
    path = plan_store.file_path(request.args.get("path"))
    plan_store.delete_file(suite, path)
    return {"deleted": path}


@bp.post("/<suite>/upload")
def upload(suite):
    """multipart: files (each file's name is its path in the suite, e.g. plans/x.yaml), optional folder, unzip."""
    files = [(f.filename or "", f.read()) for f in request.files.getlist("files")]
    stored = plan_store.upload(suite, files, folder=request.form.get("folder", ""),
                               unzip=request.form.get("unzip", "true").lower() != "false")
    return {"uploaded": stored}


@bp.get("/<suite>/download")
def download(suite):
    if not plan_store.exists(suite):
        return jsonify(error=f"suite {suite} not found"), 404
    return Response(plan_store.download_zip(suite), content_type="application/zip",
                    headers={"Content-Disposition": f'attachment; filename="{plan_store.suite_name(suite)}.zip"'})
