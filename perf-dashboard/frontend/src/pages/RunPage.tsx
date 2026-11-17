import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { api, reportUrl } from '../api'
import { PhaseBadge, PodPhase, ResultBadge } from '../components/Badges'
import { Legend, LineChart, type Series } from '../components/LineChart'
import { LogDrawer } from '../components/LogDrawer'
import { StatCard } from '../components/StatCard'
import { fmtDuration, fmtMs, fmtNum, fmtSeconds, fmtTime, pct } from '../format'
import type { LiveMetrics, PodInfo, RunSummary } from '../types'

const ACTIVE = new Set(['Pending', 'Validating', 'Setup', 'Starting', 'Running', 'Aggregating'])
const STEPS: { role: string; label: string }[] = [
  { role: 'validate', label: 'Validate plan' },
  { role: 'setup', label: 'Setup (once)' },
  { role: 'workers', label: 'Workers' },
  { role: 'report', label: 'Merge + report' },
]

interface Sample { x: number; rps: number; errps: number; users: number }

/** Turns successive cumulative totals into rates for the live chart. */
function useLiveSeries(metrics: LiveMetrics | undefined, key: string) {
  const [samples, setSamples] = useState<Sample[]>([])
  const last = useRef<{ t: number; ok: number; ko: number } | null>(null)
  const t0 = useRef<number | null>(null)
  useEffect(() => { setSamples([]); last.current = null; t0.current = null }, [key])
  useEffect(() => {
    if (!metrics?.enabled || !metrics.total || !metrics.time) return
    const { ok, ko, usersActive } = metrics.total
    const t = metrics.time
    if (t0.current === null) t0.current = t
    const prev = last.current
    last.current = { t, ok, ko }
    if (!prev || t <= prev.t) return
    const dt = t - prev.t
    const sample = { x: t - t0.current, rps: Math.max(0, (ok + ko - prev.ok - prev.ko) / dt), errps: Math.max(0, (ko - prev.ko) / dt), users: usersActive }
    setSamples((s) => [...s.slice(-200), sample])
  }, [metrics])
  return samples
}

export function RunPage() {
  const { ns = '', name = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [logPod, setLogPod] = useState<string | null>(null)

  const detail = useQuery({
    queryKey: ['loadtest', ns, name],
    queryFn: () => api.loadTest(ns, name),
    refetchInterval: (q) => (q.state.data && !ACTIVE.has(q.state.data.phase) ? 15000 : 3000),
  })
  const d = detail.data
  const active = d ? ACTIVE.has(d.phase) : false
  const live = useQuery({
    queryKey: ['metrics', ns, name],
    queryFn: () => api.metrics(ns, name),
    enabled: !!d && d.metricsEnabled && active,
    refetchInterval: 3000,
  })
  const summary = useQuery({
    queryKey: ['summary', ns, name, d?.phase],
    queryFn: () => api.summary(ns, name),
    enabled: !!d && (d.phase === 'Completed' || d.phase === 'Failed'),
    staleTime: 60000,
  })
  const samples = useLiveSeries(live.data, `${ns}/${name}`)

  if (detail.error) return <div className="alert bad">{(detail.error as Error).message} — <Link to="/">back to runs</Link></div>
  if (!d) return <p className="muted-text">loading…</p>

  const final: RunSummary | null | undefined = summary.data?.available ? summary.data.run : null
  const finalWorkers = summary.data?.available ? summary.data.workers ?? [] : []
  const liveTotal = live.data?.enabled ? live.data.total : null
  const lastSample = samples[samples.length - 1]
  const workers = d.pods.filter((p) => p.role === 'workers')
  const others = d.pods.filter((p) => p.role !== 'workers')

  const remove = async () => {
    if (!confirm(`Delete ${name}? Running workers are stopped. Results already in S3 stay.`)) return
    await api.remove(ns, name)
    await qc.invalidateQueries({ queryKey: ['loadtests'] })
    navigate('/')
  }

  const series: Series[] = [
    { name: 'requests/s', color: 'var(--accent)', points: samples.map((s) => ({ x: s.x, y: s.rps })) },
    { name: 'errors/s', color: 'var(--bad)', points: samples.map((s) => ({ x: s.x, y: s.errps })) },
  ]
  const userSeries: Series[] = [{ name: 'active users', color: 'var(--info)', points: samples.map((s) => ({ x: s.x, y: s.users })) }]

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>{name}</h1>
          <div className="sub">{ns} · plan <strong>{d.plan ?? d.planFile}</strong> · {d.workers} worker(s) · created {fmtTime(d.created)}</div>
        </div>
        <div className="actions">
          {d.report && <a className="btn" href={reportUrl(d.report)} target="_blank" rel="noreferrer">Open report</a>}
          <button className="btn danger" onClick={remove}>{active ? 'Stop & delete' : 'Delete'}</button>
        </div>
      </div>

      <div className="status-line">
        <PhaseBadge phase={d.phase} /> <ResultBadge result={d.result} />
        <span className="muted-text">{d.message}</span>
      </div>

      <div className="steps">
        {STEPS.map((s) => {
          const job = d.jobs.find((j) => j.role === s.role)
          const state = !job ? 'todo' : job.failed > 0 ? 'bad' : job.end ? 'done' : 'now'
          if (s.role === 'setup' && !job && d.phase !== 'Validating' && d.phase !== 'Pending') return null
          return (
            <div key={s.role} className={`step ${state}`}>
              <div className="step-label">{s.label}</div>
              <div className="sub">{job ? `${fmtTime(job.start)} · ${fmtDuration(job.start, job.end)}` : 'not started'}</div>
              {s.role === 'workers' && d.startAt && <div className="sub">common start {fmtTime(d.startAt)}</div>}
            </div>
          )
        })}
      </div>

      {final ? (
        <div className="cards">
          <StatCard label="Requests" value={fmtNum(final.total.count)} hint={`${fmtNum(final.total.ok)} OK · ${fmtNum(final.total.ko)} KO`} />
          <StatCard label="Errors" value={`${fmtNum(final.errorPercent, 2)} %`} tone={final.total.ko > 0 ? 'bad' : 'ok'} />
          <StatCard label="Throughput" value={`${fmtNum(final.requestsPerSecond, 1)} req/s`} hint={`over ${fmtSeconds(final.durationSeconds)}`} />
          <StatCard label="Response time p95" value={fmtMs(final.total.p95)} hint={`p50 ${fmtMs(final.total.p50)} · p99 ${fmtMs(final.total.p99)}`} />
          <StatCard label="Users" value={fmtNum(final.users)} hint={`${final.workers ?? d.workers} worker(s)`} />
          <StatCard label="SLAs" value={final.slaPassed === undefined ? '—' : final.slaPassed ? 'met' : 'failed'} tone={final.slaPassed === false ? 'bad' : final.slaPassed ? 'ok' : undefined} />
        </div>
      ) : liveTotal ? (
        <div className="cards">
          <StatCard label="Requests (live)" value={fmtNum(liveTotal.ok + liveTotal.ko)} hint={`${fmtNum(liveTotal.ok)} OK · ${fmtNum(liveTotal.ko)} KO`} />
          <StatCard label="Errors" value={`${fmtNum(pct(liveTotal.ko, liveTotal.ok + liveTotal.ko), 2)} %`} tone={liveTotal.ko > 0 ? 'bad' : 'ok'} />
          <StatCard label="Throughput" value={`${fmtNum(lastSample?.rps ?? 0, 1)} req/s`} hint="last 3 s" />
          <StatCard label="p95 (estimate)" value={fmtMs(liveTotal.p95)} hint="from histogram buckets" />
          <StatCard label="Active users" value={fmtNum(liveTotal.usersActive)} hint={`${live.data?.workers.length ?? 0} worker(s) reporting`} />
        </div>
      ) : null}

      {active && d.metricsEnabled && (
        <div className="grid2">
          <div className="card"><h3>Throughput</h3><LineChart series={series} unit="" /><Legend series={series} /></div>
          <div className="card"><h3>Active users</h3><LineChart series={userSeries} /><Legend series={userSeries} /></div>
        </div>
      )}
      {active && !d.metricsEnabled && <div className="alert">Live metrics are off for this run (spec.metrics.enabled) — final numbers appear when it completes.</div>}
      {live.data?.unreachable?.length ? <div className="alert">Not reachable yet: {live.data.unreachable.map((u) => u.pod).join(', ')}</div> : null}
      {summary.data && !summary.data.available && <div className="alert">{summary.data.reason}</div>}

      <h2>Requests</h2>
      <div className="card table-wrap">
        {final ? (
          <table>
            <thead><tr><th>Request</th><th>Count</th><th>OK</th><th>KO</th><th>Mean</th><th>p50</th><th>p95</th><th>p99</th><th>Max</th></tr></thead>
            <tbody>
              {final.requests.map((r) => (
                <tr key={r.name}>
                  <td>{r.name}</td><td>{fmtNum(r.count)}</td><td>{fmtNum(r.ok)}</td>
                  <td className={r.ko > 0 ? 'bad-text' : ''}>{fmtNum(r.ko)}</td>
                  <td>{fmtMs(r.mean)}</td><td>{fmtMs(r.p50)}</td><td>{fmtMs(r.p95)}</td><td>{fmtMs(r.p99)}</td><td>{fmtMs(r.max)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : liveTotal ? (
          <table>
            <thead><tr><th>Request</th><th>OK</th><th>KO</th><th>Mean</th><th>p95 (est.)</th></tr></thead>
            <tbody>
              {liveTotal.requests.map((r) => (
                <tr key={r.name}><td>{r.name}</td><td>{fmtNum(r.ok)}</td><td className={r.ko > 0 ? 'bad-text' : ''}>{fmtNum(r.ko)}</td><td>{fmtMs(r.mean)}</td><td>{fmtMs(r.p95)}</td></tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted-text pad">No numbers yet.</p>}
      </div>

      {final && final.transactions.length > 0 && (
        <>
          <h2>Transactions</h2>
          <div className="card table-wrap">
            <table>
              <thead><tr><th>Transaction</th><th>Count</th><th>OK</th><th>KO</th><th>Mean</th><th>p95</th><th>Max</th></tr></thead>
              <tbody>
                {final.transactions.map((t) => (
                  <tr key={t.name}><td>{t.name}</td><td>{fmtNum(t.count)}</td><td>{fmtNum(t.ok)}</td><td className={t.ko > 0 ? 'bad-text' : ''}>{fmtNum(t.ko)}</td><td>{fmtMs(t.mean)}</td><td>{fmtMs(t.p95)}</td><td>{fmtMs(t.max)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {(final?.errors.length || liveTotal?.errors.length) ? (
        <>
          <h2>Errors</h2>
          <div className="card table-wrap">
            <table>
              <thead><tr><th>Message</th><th>Count</th></tr></thead>
              <tbody>{(final?.errors ?? liveTotal?.errors ?? []).map((e) => <tr key={e.message}><td>{e.message}</td><td>{fmtNum(e.count)}</td></tr>)}</tbody>
            </table>
          </div>
        </>
      ) : null}

      <h2>Workers</h2>
      <div className="card table-wrap">
        <table>
          <thead><tr><th>#</th><th>Pod</th><th>Node</th><th>State</th><th>Restarts</th><th>Ran</th><th>Requests</th><th>KO</th><th>Users / p95</th><th /></tr></thead>
          <tbody>
            {workers.length === 0 && <tr><td colSpan={10} className="muted-text">no worker pods yet (or already cleaned up)</td></tr>}
            {workers.map((p: PodInfo) => {
              const lw = live.data?.workers.find((w) => w.pod === p.name)
              const fw = finalWorkers.find((w) => w.worker === p.worker)
              return (
                <tr key={p.name}>
                  <td>{p.worker === null ? '—' : p.worker + 1}</td>
                  <td className="mono">{p.name}</td>
                  <td>{p.node ?? '—'}</td>
                  <td><PodPhase phase={p.phase} reason={p.reason} /></td>
                  <td className={p.restarts > 0 ? 'bad-text' : ''}>{p.restarts}</td>
                  <td>{fmtDuration(p.start, p.end)}</td>
                  <td>{fw ? fmtNum(fw.total.count) : lw ? fmtNum(lw.ok + lw.ko) : '—'}</td>
                  <td>{fw ? fmtNum(fw.total.ko) : lw ? fmtNum(lw.ko) : '—'}</td>
                  <td>{fw ? `p95 ${fmtMs(fw.total.p95)}` : lw ? (lw.finished ? 'finished' : `${lw.usersActive} active`) : '—'}</td>
                  <td><button className="btn ghost" onClick={() => setLogPod(p.name)}>Log</button></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {others.length > 0 && (
        <>
          <h2>Other pods</h2>
          <div className="card table-wrap">
            <table>
              <thead><tr><th>Role</th><th>Pod</th><th>State</th><th>Ran</th><th /></tr></thead>
              <tbody>
                {others.map((p) => (
                  <tr key={p.name}>
                    <td>{p.role}</td><td className="mono">{p.name}</td><td><PodPhase phase={p.phase} reason={p.reason} /></td>
                    <td>{fmtDuration(p.start, p.end)}</td>
                    <td><button className="btn ghost" onClick={() => setLogPod(p.name)}>Log</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <details className="card spec">
        <summary>LoadTest spec · results {d.runDir ?? '—'}</summary>
        <pre>{JSON.stringify(d.spec, null, 2)}</pre>
      </details>

      {logPod && <LogDrawer ns={ns} pod={logPod} onClose={() => setLogPod(null)} />}
    </section>
  )
}
