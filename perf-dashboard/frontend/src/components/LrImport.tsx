import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { importLoadRunner } from '../api'
import type { LrImport as Result } from '../types'

/** Import a LoadRunner VuGen script (zip or folder) as a new suite - the same conversion as `perf convert-lr`. */
export function LrImport({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [suite, setSuite] = useState('')
  const [iterations, setIterations] = useState('1')
  const [files, setFiles] = useState<{ path: string; file: File }[]>([])
  const [label, setLabel] = useState('')
  const zipInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    folderInput.current?.setAttribute('webkitdirectory', '')
    folderInput.current?.setAttribute('directory', '')
  }, [])

  const pick = (list: FileList | null, folder: boolean) => {
    if (!list?.length) return
    const picked = Array.from(list).map((f) => ({ path: (folder && (f as File & { webkitRelativePath?: string }).webkitRelativePath) || f.name, file: f }))
    setFiles(picked)
    const first = picked[0].path.split('/')[0].replace(/\.zip$/i, '')
    setLabel(folder ? `folder ${first} (${picked.length} files)` : picked.map((p) => p.path).join(', '))
    // suggest a suite name from the script / zip name
    if (!suite) setSuite(first.toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^[-.]+/, '').slice(0, 63))
  }

  const run = useMutation({
    mutationFn: () => importLoadRunner(suite.trim(), files, Number(iterations) || 1),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['suites'] }) },
  })
  const submit = (e: FormEvent) => { e.preventDefault(); run.mutate() }
  const result: Result | undefined = run.data

  return (
    <div className="card lr-import">
      <div className="editor-head">
        <strong>Import LoadRunner script</strong>
        <button className="btn ghost" onClick={onClose}>Close</button>
      </div>
      {!result ? (
        <form className="form" onSubmit={submit}>
          <p className="muted-text small">
            A VuGen <strong>web (HTTP/HTML)</strong> script: its folder, or a zip of it (vuser_init.c, Action*.c, vuser_end.c,
            .prm and .dat files). It becomes a new suite with a plan, an API catalog, the data and a conversion report —
            the same conversion as <span className="mono">perf convert-lr</span>. Recording snapshots and other files are ignored.
          </p>
          <div className="row3">
            <label>Script
              <div className="actions">
                <button type="button" className="btn ghost" onClick={() => zipInput.current?.click()}>Choose zip</button>
                <button type="button" className="btn ghost" onClick={() => folderInput.current?.click()}>Choose folder</button>
              </div>
              <span className="sub">{label || 'nothing chosen'}</span>
            </label>
            <label>New suite name<input required value={suite} onChange={(e) => setSuite(e.target.value)} placeholder="e.g. lr-sr-flow" /></label>
            <label>Action iterations<input type="number" min={1} max={1000} value={iterations} onChange={(e) => setIterations(e.target.value)} /></label>
          </div>
          <input ref={zipInput} type="file" accept=".zip" hidden onChange={(e) => { pick(e.target.files, false); e.target.value = '' }} />
          <input ref={folderInput} type="file" multiple hidden onChange={(e) => { pick(e.target.files, true); e.target.value = '' }} />
          {run.error && <div className="alert bad">{(run.error as Error).message}</div>}
          <div className="actions"><button className="btn" disabled={!files.length || !suite.trim() || run.isPending}>{run.isPending ? 'Converting…' : 'Convert and save'}</button></div>
        </form>
      ) : (
        <div>
          <div className="alert">
            Created suite <strong>{result.suite}</strong> with plan <span className="mono">{result.plan}</span>
            {result.requests !== null && <> · {result.requests} request(s) converted</>}
            {result.skipped > 0 && <> · {result.skipped} non-script file(s) ignored</>}
          </div>
          <h3>Next steps</h3>
          <ol className="small">
            <li>Review the notes below — each one is something LoadRunner did that needs a decision here.</li>
            <li>Set the <strong>load</strong> in <span className="mono">{result.plan}</span> (a VuGen script has none; the converter starts with 1 user).</li>
            <li>Start it from <em>New run</em> with <span className="mono">BASE_URL</span> pointing at the environment to test.</li>
          </ol>
          <h3>Conversion notes ({result.notes.length})</h3>
          {result.notes.length ? <ul className="notes">{result.notes.map((n, i) => <li key={i} className="mono">{n}</li>)}</ul>
            : <p className="muted-text">Everything was converted.</p>}
          <div className="actions">
            <Link className="btn" to={`/plans/${result.suite}`} onClick={onClose}>Open suite</Link>
            <Link className="btn ghost" to={`/new?suite=${encodeURIComponent(result.suite)}&file=${encodeURIComponent(result.plan)}`}>Run it</Link>
            <button className="btn ghost" onClick={() => { run.reset(); setFiles([]); setLabel(''); setSuite('') }}>Import another</button>
          </div>
        </div>
      )}
    </div>
  )
}
