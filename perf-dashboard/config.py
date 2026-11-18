"""Settings, all from environment variables (set by the Helm chart)."""
import os


def _list(name, default):
    return [x.strip() for x in os.getenv(name, default).split(",") if x.strip()]


# namespaces whose LoadTests are shown (and where new runs may be created)
WATCH_NAMESPACES = _list("WATCH_NAMESPACES", "perf")

# where run results live (read by the dashboard)
S3_ENDPOINT = os.getenv("S3_ENDPOINT", "http://minio.minio.svc:9000")
S3_ACCESS_KEY = os.getenv("AWS_ACCESS_KEY_ID", "")
S3_SECRET_KEY = os.getenv("AWS_SECRET_ACCESS_KEY", "")
S3_REGION = os.getenv("AWS_REGION", "us-east-1")
RESULTS_URL = os.getenv("RESULTS_URL", "s3://perf-results/runs").rstrip("/")

# test plans managed from the dashboard: one folder (suite) per test set; same endpoint and credentials as the results
PLANS_URL = os.getenv("PLANS_URL", "s3://perf-results/test-plans").rstrip("/")
PLANS_MAX_FILE_BYTES = int(os.getenv("PLANS_MAX_FILE_MB", "20")) * 1024 * 1024
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "100")) * 1024 * 1024

# LoadRunner import: Java + the perf distribution, copied from the runner image (same converter as `perf convert-lr`)
PERF_JAVA = os.getenv("PERF_JAVA", "/opt/java/openjdk/bin/java")
PERF_HOME = os.getenv("PERF_HOME", "/opt/perf")
CONVERT_TIMEOUT_SECONDS = int(os.getenv("CONVERT_TIMEOUT_SECONDS", "60"))

# defaults for runs created from the dashboard
DEFAULT_IMAGE = os.getenv("DEFAULT_IMAGE", "labuk-perf-runner:1.0-SNAPSHOT")
DEFAULT_S3_SECRET = os.getenv("DEFAULT_S3_SECRET", "minio-creds")       # Secret the worker pods use for S3
DEFAULT_RUN_S3_ENDPOINT = os.getenv("DEFAULT_RUN_S3_ENDPOINT", S3_ENDPOINT)

# live metrics: how long to wait for one worker's /metrics
METRICS_TIMEOUT_SECONDS = float(os.getenv("METRICS_TIMEOUT_SECONDS", "2"))

# local development only: kubeconfig context (in the cluster the service account is used)
KUBE_CONTEXT = os.getenv("KUBE_CONTEXT")


def _bucket_and_prefix(url):
    rest = url[len("s3://"):] if url.startswith("s3://") else url
    bucket, _, prefix = rest.partition("/")
    return bucket, prefix.strip("/")


def results_bucket_and_prefix():
    """s3://bucket/prefix -> (bucket, prefix)"""
    return _bucket_and_prefix(RESULTS_URL)


def plans_bucket_and_prefix():
    return _bucket_and_prefix(PLANS_URL)
