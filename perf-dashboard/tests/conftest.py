import os
import sys

# tests import the app modules (app, kube, live, ...) from the dashboard folder
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
