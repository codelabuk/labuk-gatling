import { useQuery } from '@tanstack/react-query'

import { api } from '../api'

/** Side panel with a pod's log; refreshes while the pod runs. */
export function LogDrawer({ ns, pod, onClose }: { ns: string; pod: string; onClose: () => void }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ['logs', ns, pod],
    queryFn: () => api.logs(ns, pod, 1000),
    refetchInterval: 5000,
  })
  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>{pod}</strong>
          <button className="btn ghost" onClick={onClose}>Close</button>
        </header>
        <pre className="log">
          {isLoading ? 'loading…' : error ? `could not load the log: ${(error as Error).message}` : data?.log || '(empty)'}
        </pre>
      </aside>
    </div>
  )
}
