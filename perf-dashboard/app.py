"""perf-dashboard: web UI for distributed load tests (LoadTest resources) - API + the built React app."""
import os

from flask import Flask, jsonify, send_from_directory
from werkzeug.exceptions import HTTPException

import config
from routes import BLUEPRINTS

DIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", "dist")


def create_app():
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = config.MAX_UPLOAD_BYTES   # uploads of test files / zips
    for bp in BLUEPRINTS:
        app.register_blueprint(bp)

    @app.errorhandler(HTTPException)
    def http_error(e):
        return jsonify(error=e.description), e.code

    # the React app; unknown paths fall back to index.html (client-side routing)
    @app.get("/", defaults={"path": ""})
    @app.get("/<path:path>")
    def spa(path):
        if path.startswith(("api/", "reports/")):
            return jsonify(error="not found"), 404
        full = os.path.join(DIST, path)
        if path and os.path.isfile(full):
            return send_from_directory(DIST, path)
        if os.path.isfile(os.path.join(DIST, "index.html")):
            return send_from_directory(DIST, "index.html")
        return "perf-dashboard API is running; the UI is not built (cd frontend && npm run build)", 200

    return app


if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=int(os.getenv("PORT", "5000")), debug=os.getenv("FLASK_DEBUG") == "1")
