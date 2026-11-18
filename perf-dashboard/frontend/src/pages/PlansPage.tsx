import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { api, suiteDownloadUrl } from '../api'
import { fmtNum, fmtTime } from '../format'
import type { SuiteFile } from '../types'

const fmtSize = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${fmtNum(n / 1024, 1)} KB` : `${fmtNum(n / 1024 / 1024, 1)} MB`)

/** Test plans kept in S3: suites (folders) of plans, API catalogs and data - created, uploaded and edited here. */
export function PlansPage() {
  const { suite } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const suites = useQuery({ queryKey: ['suites'], queryFn: api.suites })
  const [newName, setNewName] = useState('')

  const create = useMutation({
    mutationFn: () => api.createSuite(newName.trim()),
    onSuccess: async (s) => {
      setNewName('')
      await qc.invalidateQueries({ queryKey: ['suites'] })
      navigate(`/plans/${s.name}`)
    },
  })
  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate() }

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Test plans</h1>
          <div className="sub">Saved in the results bucket; a run takes a copy, so editing here never changes a running test.</div>
        </div>
      </div>
      <div className="plans">
        <div className="card suites">
          <form className="form new-suite" onSubmit={submit}>
            <label>New suite<input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="e.g. orders-api" /></label>
            <button className="btn" disabled={!newName.trim() || create.isPending}>Create</button>
          </form>
          {create.error && <div className="alert bad">{(create.error as Error).message}</div>}
          {suites.error && <div className="alert bad">{(suites.error as Error).message}</div>}
          {suites.isLoading && <p className="muted-text">loading…</p>}
          {suites.data?.length === 0 && <p className="muted-text">No suites yet - create one or upload a zip of a test folder.</p>}
          <ul className="suite-list">
            {suites.data?.map((s) => (
              <li key={s.name}>
                <Link className={s.name === suite ? 'active' : ''} to={`/plans/${s.name}`}>
                  <strong>{s.name}</strong>
                  <span className="sub">{s.files} file(s) · {fmtSize(s.size)} · {fmtTime(s.modified)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
        <div>{suite ? <SuiteView key={suite} suite={suite} /> : <div className="card empty">Choose a suite on the left, or create one.</div>}</div>
      </div>
    </section>
  )
}

function SuiteView({ suite }: { suite: string }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['suite', suite], queryFn: () => api.suite(suite) })
  const [editing, setEditing] = useState<string | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const filesInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    // the folder picker is non-standard: set the attributes directly
    folderInput.current?.setAttribute('webkitdirectory', '')
    folderInput.current?.setAttribute('directory', '')
  }, [])

  const refresh = async () => {
    await qc.invalidateQueries({ queryKey: ['suite', suite] })
    await qc.invalidateQueries({ queryKey: ['suites'] })
  }

  const upload = useMutation({
    mutationFn: (files: { path: string; file: File }[]) => api.upload(suite, files),
    onSuccess: async (r) => { setMessage({ ok: true, text: `Uploaded ${r.uploaded.length} file(s).` }); await refresh() },
    onError: (e) => setMessage({ ok: false, text: (e as Error).message }),
  })

  const pickFiles = (list: FileList | null, fromFolder: boolean) => {
    if (!list?.length) return
    // a picked folder arrives as "<folder>/plans/x.yaml": the folder itself is the suite, so drop its name
    const files = Array.from(list).map((f) => {
      const rel = (f as File & { webkitRelativePath?: string }).webkitRelativePath
      return { path: fromFolder && rel ? rel.split('/').slice(1).join('/') : f.name, file: f }
    }).filter((f) => f.path && !f.path.split('/').some((p) => p.startsWith('.')))
    upload.mutate(files)
  }

  const removeFile = async (f: SuiteFile) => {
    if (!confirm(`Delete ${f.path} from ${suite}?`)) return
    try { await api.removeFile(suite, f.path); setMessage({ ok: true, text: `Deleted ${f.path}.` }); await refresh() }
    catch (e) { setMessage({ ok: false, text: (e as Error).message }) }
  }

  const removeSuite = async () => {
    if (!confirm(`Delete the whole suite ${suite} (${detail.data?.files.length ?? 0} files)? Runs already started keep their copy.`)) return
    try { await api.removeSuite(suite); await qc.invalidateQueries({ queryKey: ['suites'] }); navigate('/plans') }
    catch (e) { setMessage({ ok: false, text: (e as Error).message }) }
  }

  const newFile = () => {
    const path = prompt('Path of the new file in the suite, e.g. plans/checkout.yaml or data/users.csv')?.trim()
    if (path) setEditing(path)
  }

  if (detail.error) return <div className="alert bad">{(detail.error as Error).message}</div>
  if (!detail.data) return <p className="muted-text">loading…</p>
  const plans = detail.data.files.filter((f) => f.plan)

  return (
    <div className="card">
      <div className="page-head">
        <div>
          <h2 className="flush">{suite}</h2>
          <div className="sub mono">{detail.data.url}</div>
        </div>
        <div className="actions wrap">
          <button className="btn ghost" onClick={() => filesInput.current?.click()} disabled={upload.isPending}>Upload files / zip</button>
          <button className="btn ghost" onClick={() => folderInput.current?.click()} disabled={upload.isPending}>Upload folder</button>
          <button className="btn ghost" onClick={newFile}>New file</button>
          <a className="btn ghost" href={suiteDownloadUrl(suite)}>Download zip</a>
          <button className="btn danger" onClick={removeSuite}>Delete suite</button>
        </div>
      </div>
      <input ref={filesInput} type="file" multiple hidden onChange={(e) => { pickFiles(e.target.files, false); e.target.value = '' }} />
      <input ref={folderInput} type="file" multiple hidden onChange={(e) => { pickFiles(e.target.files, true); e.target.value = '' }} />
      <p className="muted-text small">
        Single files land at the top of the suite - use <em>Upload folder</em> or a zip to keep sub-folders (plans/, apis/, data/).
        A zip of one folder is unpacked into the suite without that folder's name.
      </p>
      {upload.isPending && <div className="alert">Uploading…</div>}
      {message && <div className={`alert ${message.ok ? '' : 'bad'}`}>{message.text}</div>}

      {editing && <Editor suite={suite} path={editing} onClose={() => setEditing(null)} onSaved={async (p) => { setMessage({ ok: true, text: `Saved ${p}.` }); await refresh() }} />}

      <div className="table-wrap">
        <table>
          <thead><tr><th>File</th><th>Size</th><th>Modified</th><th /></tr></thead>
          <tbody>
            {detail.data.files.map((f) => (
              <tr key={f.path}>
                <td className="mono">{f.path} {f.plan && <span className="badge info">plan</span>}</td>
                <td>{fmtSize(f.size)}</td>
                <td>{fmtTime(f.modified)}</td>
                <td className="actions">
                  {f.plan && <Link className="btn ghost" to={`/new?suite=${encodeURIComponent(suite)}&file=${encodeURIComponent(f.path)}`}>Run</Link>}
                  {f.text && <button className="btn ghost" onClick={() => setEditing(f.path)}>Edit</button>}
                  <button className="btn ghost danger-text" onClick={() => removeFile(f)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {plans.length === 0 && <p className="muted-text">No plan in this suite yet (a plan is a YAML file with <span className="mono">scenarios:</span>).</p>}
    </div>
  )
}

function Editor({ suite, path, onClose, onSaved }: { suite: string; path: string; onClose: () => void; onSaved: (path: string) => void }) {
  const file = useQuery({
    queryKey: ['file', suite, path],
    // a new file does not exist yet: start empty
    queryFn: () => api.readFile(suite, path).catch((e: Error) => (e.message.includes('not found') ? { path, size: 0, content: '', editable: true } : Promise.reject(e))),
    gcTime: 0,
  })
  const [text, setText] = useState<string | null>(null)
  const value = text ?? file.data?.content ?? ''
  const dirty = text !== null && text !== file.data?.content

  const save = useMutation({
    mutationFn: () => api.saveFile(suite, path, value),
    onSuccess: (r) => { onSaved(r.saved); onClose() },
  })

  const close = () => { if (!dirty || confirm('Discard your changes?')) onClose() }

  return (
    <div className="editor">
      <div className="editor-head">
        <strong className="mono">{path}</strong>{dirty && <span className="muted-text"> · unsaved</span>}
        <div className="actions">
          <button className="btn" onClick={() => save.mutate()} disabled={save.isPending || !file.data?.editable}>{save.isPending ? 'Saving…' : 'Save'}</button>
          <button className="btn ghost" onClick={close}>Close</button>
        </div>
      </div>
      {file.error && <div className="alert bad">{(file.error as Error).message}</div>}
      {save.error && <div className="alert bad">{(save.error as Error).message}</div>}
      {file.data && !file.data.editable && <div className="alert">This file is binary or larger than 2 MB - download the suite to change it.</div>}
      {file.data?.editable && (
        <textarea className="code" spellCheck={false} value={value} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); save.mutate() }
            if (e.key === 'Tab') {   // YAML is indented with spaces
              e.preventDefault()
              const t = e.currentTarget, s = t.selectionStart
              setText(value.slice(0, s) + '  ' + value.slice(t.selectionEnd))
              requestAnimationFrame(() => { t.selectionStart = t.selectionEnd = s + 2 })
            }
          }} />
      )}
      <div className="sub">Saving checks the YAML syntax; the full plan check (APIs, data files, SLAs) runs when a run starts. Ctrl+S saves.</div>
    </div>
  )
}
