"""Serves Gatling HTML reports straight from S3 / MinIO, so a report opens in the browser."""
import mimetypes
import posixpath

from flask import Blueprint, Response, abort, redirect
from minio.error import S3Error

import config
import storage

bp = Blueprint("reports", __name__, url_prefix="/reports")


def safe_key(path):
    """Only files under the results prefix; no '..', no absolute paths. Returns the object key or None."""
    _, prefix = config.results_bucket_and_prefix()
    if not path or "\\" in path or path.startswith("/"):
        return None
    norm = posixpath.normpath(path)
    if norm != path.rstrip("/") or norm.startswith("..") or "/../" in f"/{norm}/":
        return None
    if prefix and not (norm == prefix or norm.startswith(prefix + "/")):
        return None
    return norm


@bp.get("/<path:path>")
def report_file(path):
    if path.endswith("/"):
        return redirect(f"/reports/{path}index.html")
    key = safe_key(path)
    if key is None:
        abort(404)
    try:
        stream, length = storage.report_object(key)
    except S3Error as e:
        if e.code in ("NoSuchKey", "NoSuchBucket"):
            abort(404)
        raise
    content_type = mimetypes.guess_type(key)[0] or "application/octet-stream"

    def body():
        try:
            for chunk in stream.stream(64 * 1024):
                yield chunk
        finally:
            stream.close()
            stream.release_conn()

    headers = {"Cache-Control": "private, max-age=300"}
    if length:
        headers["Content-Length"] = str(length)
    return Response(body(), content_type=content_type, headers=headers)
