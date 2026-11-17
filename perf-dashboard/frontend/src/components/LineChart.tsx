/** A small dependency-free line chart for live series (x = seconds since the first sample). */
export interface Series { name: string; color: string; points: { x: number; y: number }[] }

export function LineChart({ series, height = 180, unit = '' }: { series: Series[]; height?: number; unit?: string }) {
  const width = 640
  const pad = { l: 46, r: 12, t: 10, b: 22 }
  const all = series.flatMap((s) => s.points)
  if (all.length < 2) return <div className="chart-empty">collecting samples…</div>
  const maxX = Math.max(...all.map((p) => p.x), 1)
  const maxY = Math.max(...all.map((p) => p.y), 1) * 1.1
  const sx = (x: number) => pad.l + (x / maxX) * (width - pad.l - pad.r)
  const sy = (y: number) => height - pad.b - (y / maxY) * (height - pad.t - pad.b)
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * maxY)
  return (
    <svg className="chart" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
      {ticks.map((t) => (
        <g key={t}>
          <line x1={pad.l} x2={width - pad.r} y1={sy(t)} y2={sy(t)} className="grid" />
          <text x={pad.l - 6} y={sy(t) + 4} textAnchor="end" className="axis">{t >= 10 ? Math.round(t) : t.toFixed(1)}{unit}</text>
        </g>
      ))}
      <text x={width - pad.r} y={height - 4} textAnchor="end" className="axis">{Math.round(maxX)}s</text>
      {series.map((s) => (
        <polyline key={s.name} fill="none" stroke={s.color} strokeWidth={2}
          points={s.points.map((p) => `${sx(p.x)},${sy(p.y)}`).join(' ')} />
      ))}
    </svg>
  )
}

export function Legend({ series }: { series: Series[] }) {
  return (
    <div className="legend">
      {series.map((s) => <span key={s.name}><i style={{ background: s.color }} />{s.name}</span>)}
    </div>
  )
}
