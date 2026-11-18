"""In-memory stand-in for the MinIO client: just the calls the dashboard makes, with S3's prefix semantics."""
from datetime import datetime, timezone
from types import SimpleNamespace

from minio.error import S3Error


def _missing(key):
    return S3Error("NoSuchKey", "The specified key does not exist.", key, "req", "host", None)


class FakeMinio:
    def __init__(self):
        self.objects = {}   # (bucket, key) -> bytes

    # writes
    def put_object(self, bucket, key, data, length, content_type=None):
        self.objects[(bucket, key)] = data.read(length)

    def remove_object(self, bucket, key):
        self.objects.pop((bucket, key), None)

    def remove_objects(self, bucket, delete_list):
        for d in delete_list:
            self.objects.pop((bucket, d._name), None)
        return iter(())

    # reads
    def stat_object(self, bucket, key):
        if (bucket, key) not in self.objects:
            raise _missing(key)
        return SimpleNamespace(size=len(self.objects[(bucket, key)]))

    def get_object(self, bucket, key):
        if (bucket, key) not in self.objects:
            raise _missing(key)
        data = self.objects[(bucket, key)]
        return SimpleNamespace(read=lambda: data, close=lambda: None, release_conn=lambda: None,
                               headers={"Content-Length": str(len(data))})

    def list_objects(self, bucket, prefix="", recursive=False):
        when = datetime(2026, 11, 18, 10, 0, tzinfo=timezone.utc)
        seen = set()
        for (b, key), data in sorted(self.objects.items()):
            if b != bucket or not key.startswith(prefix):
                continue
            rest = key[len(prefix):]
            if not recursive and "/" in rest:
                folder = prefix + rest.split("/", 1)[0] + "/"
                if folder not in seen:
                    seen.add(folder)
                    yield SimpleNamespace(object_name=folder, is_dir=True, size=0, last_modified=None)
                continue
            yield SimpleNamespace(object_name=key, is_dir=False, size=len(data), last_modified=when)

    def keys(self, bucket="perf-results"):
        return sorted(k for b, k in self.objects if b == bucket)
