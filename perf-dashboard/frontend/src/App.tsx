import { NavLink, Route, Routes } from 'react-router-dom'

import { HistoryPage } from './pages/HistoryPage'
import { NewRunPage } from './pages/NewRunPage'
import { RunPage } from './pages/RunPage'
import { RunsPage } from './pages/RunsPage'

export function App() {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">perf<span>dashboard</span></div>
        <nav>
          <NavLink to="/" end>Runs</NavLink>
          <NavLink to="/new">New run</NavLink>
          <NavLink to="/history">History</NavLink>
        </nav>
        <div className="sidebar-foot">Gatling load tests on Kubernetes</div>
      </aside>
      <main>
        <Routes>
          <Route path="/" element={<RunsPage />} />
          <Route path="/new" element={<NewRunPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/runs/:ns/:name" element={<RunPage />} />
          <Route path="*" element={<p>Not found.</p>} />
        </Routes>
      </main>
    </div>
  )
}
