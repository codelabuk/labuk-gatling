import math

import live

# what a worker's /metrics returns (format of org.codelabuk.perf.metrics.LiveMetrics)
SAMPLE = """# HELP perf_requests_total Requests by name and status (OK/KO)
# TYPE perf_requests_total counter
perf_requests_total{plan="sr",worker="0",request="login",status="OK"} 10
perf_requests_total{plan="sr",worker="0",request="sr-end-to-end / getSrStatus",status="OK"} 30
perf_requests_total{plan="sr",worker="0",request="sr-end-to-end / getSrStatus",status="KO"} 2
# TYPE perf_response_time_ms histogram
perf_response_time_ms_bucket{plan="sr",worker="0",request="login",le="10"} 2
perf_response_time_ms_bucket{plan="sr",worker="0",request="login",le="50"} 8
perf_response_time_ms_bucket{plan="sr",worker="0",request="login",le="100"} 10
perf_response_time_ms_bucket{plan="sr",worker="0",request="login",le="+Inf"} 10
perf_response_time_ms_sum{plan="sr",worker="0",request="login"} 400
perf_response_time_ms_count{plan="sr",worker="0",request="login"} 10
perf_users_active{plan="sr",worker="0",scenario="a"} 3
perf_users_active{plan="sr",worker="0",scenario="b"} 2
perf_errors_total{plan="sr",worker="0",message="status.find.in(200), but actually found 500"} 2
"""


def test_parse_handles_labels_with_spaces_and_comments():
    samples = live.parse(SAMPLE)
    names = {s[0] for s in samples}
    assert "perf_requests_total" in names and "perf_users_active" in names
    req = [s for s in samples if s[0] == "perf_requests_total" and s[1]["request"] == "sr-end-to-end / getSrStatus"]
    assert {s[1]["status"]: s[2] for s in req} == {"OK": 30.0, "KO": 2.0}


def test_summarize_and_json():
    s = live.summarize(live.parse(SAMPLE))
    j = live.as_json(s)
    assert j["ok"] == 40 and j["ko"] == 2 and j["usersActive"] == 5
    login = next(r for r in j["requests"] if r["name"] == "login")
    assert login["mean"] == 40
    assert j["errors"][0]["count"] == 2


def test_quantile_interpolates_like_prometheus():
    buckets = {10.0: 2, 50.0: 8, 100.0: 10, math.inf: 10}
    # 95% of 10 = 9.5 -> inside (50, 100]: 50 + 50 * (9.5 - 8) / (10 - 8) = 87.5
    assert live.quantile(0.95, buckets) == 87.5
    assert live.quantile(0.5, {10.0: 0, math.inf: 0}) is None
    # everything above the last finite bucket -> that bucket's bound
    assert live.quantile(0.99, {10.0: 1, math.inf: 100}) == 10.0


def test_finished_workers_keep_counting(monkeypatch):
    """A worker that ended can't be scraped any more - its last numbers stay in the totals (they never go down)."""
    class Resp:
        text = SAMPLE
        def raise_for_status(self): pass

    up = {"10.0.0.1", "10.0.0.2"}
    def fake_get(url, timeout):
        if url.split("//")[1].split(":")[0] not in up:
            raise ConnectionError("gone")
        return Resp()
    monkeypatch.setattr(live.requests, "get", fake_get)
    pods = [{"name": "w-0", "worker": 0, "role": "workers", "podIP": "10.0.0.1", "phase": "Running"},
            {"name": "w-1", "worker": 1, "role": "workers", "podIP": "10.0.0.2", "phase": "Running"}]
    first = live.scrape(pods, 9464, run_key="perf/t1")
    assert first["total"]["ok"] == 80 and first["total"]["usersActive"] == 10

    pods[1]["phase"] = "Succeeded"           # worker 2 finished
    second = live.scrape(pods, 9464, run_key="perf/t1")
    assert second["total"]["ok"] == 80       # still counted
    assert second["total"]["usersActive"] == 5   # but no longer active
    assert [w["finished"] for w in second["workers"]] == [False, True]


def test_merge_sums_workers():
    w = live.summarize(live.parse(SAMPLE))
    total = live.as_json(live.merge([w, w, w]))
    assert total["ok"] == 120 and total["ko"] == 6 and total["usersActive"] == 15
    assert total["p95"] is not None
