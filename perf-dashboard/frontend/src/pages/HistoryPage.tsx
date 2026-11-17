import { useQuery } from '@tanstack/react-query'

import { api, reportUrl } from '../api'
import { ResultBadge } from '../components/Badges'
import { fmtMs, fmtNum, fmtSeconds, fmtTime } from '../format'

/** Every run folder in S3/MinIO - including runs whose LoadTest was deleted. */
export function HistoryPage() {
  const { data, error, isLoading } = useQuery({ queryKey: ['runs'], queryFn: api.runs, refetchInterval: 30000 })
  return (
    <section>
      <div className="page-head"><h1>History</h1></div>
      {error && <div className="alert bad">{(error as Error).message}</div>}
      {isLoading ? <p className="muted-text">loading…</p> : (
        <div className="card table-wrap">
          <table>
            <thead><tr><th>Run</th><th>Started</th><th>Duration</th><th>Workers</th><th>Users</th><th>Requests</th><th>Errors</th><th>p95</th><th>Req/s</th><th>SLAs</th><th /></tr></thead>
            <tbody>
              {!data?.length && <tr><td colSpan={11} className="muted-text">no runs in the results bucket yet</td></tr>}
              {data?.map((r) => {
                const s = r.summary
                return (
                  <tr key={r.url}>
                    <td className="mono">{r.folder}</td>
                    <td>{fmtTime(s?.start)}</td>
                    <td>{s ? fmtSeconds(s.durationSeconds) : '—'}</td>
                    <td>{s?.workers ?? '—'}</td>
                    <td>{fmtNum(s?.users)}</td>
                    <td>{fmtNum(s?.total.count)}</td>
                    <td className={s && s.total.ko > 0 ? 'bad-text' : ''}>{s ? `${fmtNum(s.errorPercent, 2)} %` : '—'}</td>
                    <td>{fmtMs(s?.total.p95)}</td>
                    <td>{fmtNum(s?.requestsPerSecond, 1)}</td>
                    <td>{s?.slaPassed === undefined ? <span className="muted-text">{s ? '—' : 'no summary'}</span> : <ResultBadge result={s.slaPassed ? 'Passed' : 'SLAFailed'} />}</td>
                    <td>{r.report && <a className="btn ghost" href={reportUrl(r.report)} target="_blank" rel="noreferrer">Report</a>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
