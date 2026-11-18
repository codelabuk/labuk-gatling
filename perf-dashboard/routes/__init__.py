from routes.api import bp as api_bp
from routes.plans import bp as plans_bp
from routes.reports import bp as reports_bp

BLUEPRINTS = [plans_bp, api_bp, reports_bp]
