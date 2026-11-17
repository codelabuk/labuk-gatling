import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'

import { api } from '../api'

/** "KEY=value" lines -> object; blank lines and lines without '=' are ignored. */
const parseEnv = (text: string) =>
  Object.fromEntries(text.split('\n').map((l) => l.trim()).filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
const parseList = (text: string) => text.split(/[,\n]/).map((s) => s.trim()).filter(Boolean)

export function NewRunPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const cfg = useQuery({ queryKey: ['config'], queryFn: api.config })
  const [source, setSource] = useState<'git' | 'configMap'>('git')
  const [form, setForm] = useState({
    namespace: '', name: '', image: '', file: '', repo: '', ref: 'main', tokenSecret: '', configMap: '',
    workers: '2', env: '', secrets: '', scenarios: '', cpu: '', memory: '1Gi', startDelaySeconds: '30', metrics: true,
  })
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })

  const create = useMutation({
    mutationFn: () => api.create({
      namespace: form.namespace || cfg.data?.namespaces[0],
      name: form.name || undefined,
      image: form.image || undefined,
      workers: Number(form.workers),
      plan: source === 'git'
        ? { file: form.file, git: { repo: form.repo, ref: form.ref, tokenSecret: form.tokenSecret || undefined } }
        : { file: form.file, configMap: form.configMap },
      env: parseEnv(form.env),
      secretRefs: parseList(form.secrets),
      scenarios: parseList(form.scenarios),
      cpu: form.cpu || undefined,
      memory: form.memory || undefined,
      startDelaySeconds: Number(form.startDelaySeconds) || undefined,
      metrics: form.metrics,
    }),
    onSuccess: async (row) => {
      await qc.invalidateQueries({ queryKey: ['loadtests'] })
      navigate(`/runs/${row.namespace}/${row.name}`)
    },
  })

  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate() }

  return (
    <section>
      <div className="page-head"><h1>New run</h1></div>
      <form className="card form" onSubmit={submit}>
        <fieldset>
          <legend>Test plan</legend>
          <div className="seg">
            <button type="button" className={source === 'git' ? 'on' : ''} onClick={() => setSource('git')}>Git repository</button>
            <button type="button" className={source === 'configMap' ? 'on' : ''} onClick={() => setSource('configMap')}>ConfigMap</button>
          </div>
          {source === 'git' ? (
            <div className="row3">
              <label>Repository URL<input required value={form.repo} onChange={set('repo')} placeholder="https://git.example.com/qa/perf-tests.git" /></label>
              <label>Branch, tag or commit<input value={form.ref} onChange={set('ref')} /></label>
              <label>Token secret (private repo)<input value={form.tokenSecret} onChange={set('tokenSecret')} placeholder="git-creds" /></label>
            </div>
          ) : (
            <label>ConfigMap with the test files<input required value={form.configMap} onChange={set('configMap')} placeholder="sr-tests" /></label>
          )}
          <label>Plan file<input required value={form.file} onChange={set('file')} placeholder={source === 'git' ? 'plans/orders.yaml (relative to the repository)' : 'orders.yaml (a key of the ConfigMap)'} /></label>
        </fieldset>

        <fieldset>
          <legend>Load</legend>
          <div className="row3">
            <label>Workers (pods)<input type="number" min={1} max={200} value={form.workers} onChange={set('workers')} /></label>
            <label>CPU per worker<input value={form.cpu} onChange={set('cpu')} placeholder="e.g. 1 (empty = no limit)" /></label>
            <label>Memory per worker<input value={form.memory} onChange={set('memory')} /></label>
          </div>
          <div className="row3">
            <label>Start delay (s)<input type="number" min={5} value={form.startDelaySeconds} onChange={set('startDelaySeconds')} /></label>
            <label>Scenarios (empty = all)<input value={form.scenarios} onChange={set('scenarios')} placeholder="checkout, browse" /></label>
            <label className="check"><input type="checkbox" checked={form.metrics} onChange={(e) => setForm({ ...form, metrics: e.target.checked })} /> Live metrics</label>
          </div>
        </fieldset>

        <fieldset>
          <legend>Environment</legend>
          <label>Variables — one NAME=value per line (fill ${'{'}NAME{'}'} in the plan)<textarea rows={3} value={form.env} onChange={set('env')} placeholder="BASE_URL=https://sit.api.example.com" /></label>
          <label>Secrets (as environment variables)<input value={form.secrets} onChange={set('secrets')} placeholder="perf-secrets" /></label>
        </fieldset>

        <fieldset>
          <legend>Run</legend>
          <div className="row3">
            <label>Namespace<select value={form.namespace} onChange={set('namespace')}>
              {(cfg.data?.namespaces ?? []).map((n) => <option key={n}>{n}</option>)}
            </select></label>
            <label>Name (optional)<input value={form.name} onChange={set('name')} placeholder="from the plan file + time" /></label>
            <label>Runner image<input value={form.image} onChange={set('image')} placeholder={cfg.data?.defaults.image} /></label>
          </div>
          <p className="muted-text">Results go to {cfg.data?.resultsUrl ?? '…'}.</p>
        </fieldset>

        {create.error && <div className="alert bad">{(create.error as Error).message}</div>}
        <div className="actions"><button className="btn" disabled={create.isPending}>{create.isPending ? 'Starting…' : 'Start run'}</button></div>
      </form>
    </section>
  )
}
