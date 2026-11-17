import type { Phase } from '../types'

const phaseClass: Record<string, string> = {
  Pending: 'muted', Validating: 'info', Setup: 'info', Starting: 'info', Running: 'run',
  Aggregating: 'info', Completed: 'ok', Failed: 'bad',
}

export function PhaseBadge({ phase }: { phase: Phase | string | null }) {
  const p = phase ?? 'Pending'
  return <span className={`badge ${phaseClass[p] ?? 'muted'}`}>{p === 'Running' && <span className="pulse" />}{p}</span>
}

export function ResultBadge({ result }: { result: string | null | undefined }) {
  if (!result) return <span className="muted-text">—</span>
  return <span className={`badge ${result === 'Passed' ? 'ok' : 'bad'}`}>{result === 'SLAFailed' ? 'SLA failed' : result}</span>
}

export function PodPhase({ phase, reason }: { phase: string | null; reason?: string | null }) {
  const cls = phase === 'Running' ? 'run' : phase === 'Succeeded' ? 'ok' : phase === 'Failed' ? 'bad' : 'muted'
  return <span className={`badge ${cls}`} title={reason ?? undefined}>{phase ?? '?'}{reason && phase !== 'Succeeded' ? ` · ${reason}` : ''}</span>
}
