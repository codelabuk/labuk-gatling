import type { DashboardConfig, LiveMetrics, LoadTestDetail, LoadTestRow, RunFolder, SummaryResponse } from './types'

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error((body as { error?: string }).error ?? `${res.status} ${res.statusText}`)
  return body as T
}

export const api = {
  config: () => call<DashboardConfig>('/api/config'),
  loadTests: () => call<LoadTestRow[]>('/api/loadtests'),
  loadTest: (ns: string, name: string) => call<LoadTestDetail>(`/api/loadtests/${ns}/${name}`),
  metrics: (ns: string, name: string) => call<LiveMetrics>(`/api/loadtests/${ns}/${name}/metrics`),
  summary: (ns: string, name: string) => call<SummaryResponse>(`/api/loadtests/${ns}/${name}/summary`),
  logs: (ns: string, pod: string, tail = 500) => call<{ pod: string; log: string }>(`/api/pods/${ns}/${pod}/logs?tail=${tail}`),
  create: (body: unknown) => call<LoadTestRow>('/api/loadtests', { method: 'POST', body: JSON.stringify(body) }),
  remove: (ns: string, name: string) => call<{ deleted: string }>(`/api/loadtests/${ns}/${name}`, { method: 'DELETE' }),
  runs: () => call<RunFolder[]>('/api/runs'),
}

/** URL of a report served by the dashboard from S3/MinIO. */
export const reportUrl = (key: string) => `/reports/${key}`
