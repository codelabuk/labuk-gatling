import type { DashboardConfig, FileContent, LiveMetrics, LrImport, LoadTestDetail, LoadTestRow, RunFolder, Suite, SuiteDetail, SummaryResponse } from './types'

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
  remove: (ns: string, name: string, withResults = false) =>
    call<{ deleted: string; resultObjects?: number }>(`/api/loadtests/${ns}/${name}${withResults ? '?results=true' : ''}`, { method: 'DELETE' }),
  runs: () => call<RunFolder[]>('/api/runs'),
  removeRun: (folder: string) => call<{ deleted: string; objects: number }>(`/api/runs/${folder}`, { method: 'DELETE' }),

  // saved test plans (suites in S3)
  suites: () => call<Suite[]>('/api/plans'),
  suite: (suite: string) => call<SuiteDetail>(`/api/plans/${suite}/files`),
  createSuite: (name: string) => call<SuiteDetail>('/api/plans', { method: 'POST', body: JSON.stringify({ name }) }),
  removeSuite: (suite: string) => call<{ deleted: string }>(`/api/plans/${suite}`, { method: 'DELETE' }),
  readFile: (suite: string, path: string) => call<FileContent>(`/api/plans/${suite}/file?path=${encodeURIComponent(path)}`),
  saveFile: (suite: string, path: string, content: string) =>
    call<{ saved: string }>(`/api/plans/${suite}/file?path=${encodeURIComponent(path)}`, { method: 'PUT', body: JSON.stringify({ content }) }),
  removeFile: (suite: string, path: string) =>
    call<{ deleted: string }>(`/api/plans/${suite}/file?path=${encodeURIComponent(path)}`, { method: 'DELETE' }),
  /** Each file is sent under its path in the suite (e.g. plans/x.yaml); zips are unpacked by the server. */
  upload: async (suite: string, files: { path: string; file: File }[], folder = '') => {
    const form = new FormData()
    files.forEach((f) => form.append('files', f.file, f.path))
    if (folder) form.append('folder', folder)
    const res = await fetch(`/api/plans/${suite}/upload`, { method: 'POST', body: form })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error((body as { error?: string }).error ?? `${res.status} ${res.statusText}`)
    return body as { uploaded: string[] }
  },
}

/** Converts a VuGen script (its folder's files, or a zip) into a new suite - same converter as `perf convert-lr`. */
export async function importLoadRunner(suite: string, files: { path: string; file: File }[], iterations: number): Promise<LrImport> {
  const form = new FormData()
  form.append('suite', suite)
  form.append('iterations', String(iterations))
  files.forEach((f) => form.append('files', f.file, f.path))
  const res = await fetch('/api/plans/import-lr', { method: 'POST', body: form })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error((body as { error?: string }).error ?? `${res.status} ${res.statusText}`)
  return body as LrImport
}

export const suiteDownloadUrl = (suite: string) => `/api/plans/${suite}/download`

/** URL of a report served by the dashboard from S3/MinIO. */
export const reportUrl = (key: string) => `/reports/${key}`
