import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'

import { api, reportUrl } from '../api'
import { PhaseBadge, ResultBadge } from '../components/Badges'
import { fmtDuration, fmtTime } from '../format'

export function RunsPage() {
  const { data, error, isLoading } = useQuery({ queryKey: ['loadtests'], queryFn: api.loadTests, refetchInterval: 5000 })

  return (
    <section>
      <div className="page-head">
        <h1>Runs</h1>
        <Link className="btn" to="/new">New run</Link>
      </div>
      {error && <div className="alert bad">{(error as Error).message}</div>}
      {isLoading ? <p className="muted-text">loading…</p> : !data?.length ? (
        <div className="card empty">No load tests yet. <Link to="/new">Start one</Link>.</div>
      ) : (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr><th>Name</th><th>Plan</th><th>Phase</th><th>Result</th><th>Workers</th><th>Started</th><th>Duration</th><th /></tr>
            </thead>
            <tbody>
              {data.map((r) => (
                <tr key={`${r.namespace}/${r.name}`}>
                  <td><Link to={`/runs/${r.namespace}/${r.name}`}>{r.name}</Link><div className="sub">{r.namespace}</div></td>
                  <td>{r.plan ?? '—'}<div className="sub">{r.planFile}</div></td>
                  <td><PhaseBadge phase={r.phase} /><div className="sub ellipsis" title={r.message ?? ''}>{r.message}</div></td>
                  <td><ResultBadge result={r.result} /></td>
                  <td>{r.workersSucceeded}/{r.workers}{r.workersFailed > 0 && <span className="bad-text"> ({r.workersFailed} failed)</span>}</td>
                  <td>{fmtTime(r.startedAt ?? r.created)}</td>
                  <td>{fmtDuration(r.startedAt ?? r.created, r.finishedAt)}</td>
                  <td>{r.report && <a className="btn ghost" href={reportUrl(r.report)} target="_blank" rel="noreferrer">Report</a>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
