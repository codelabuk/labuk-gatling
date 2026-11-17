export const fmtTime = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' }) : '—'

export function fmtDuration(fromIso: string | null | undefined, toIso?: string | null): string {
  if (!fromIso) return '—'
  const ms = (toIso ? new Date(toIso).getTime() : Date.now()) - new Date(fromIso).getTime()
  if (ms < 0) return '—'
  const s = Math.round(ms / 1000)
  return s < 60 ? `${s}s` : s < 3600 ? `${Math.floor(s / 60)}m ${s % 60}s` : `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
}

export const fmtSeconds = (sec: number) => fmtDuration(new Date(0).toISOString(), new Date(sec * 1000).toISOString())

export const fmtNum = (n: number | null | undefined, digits = 0) =>
  n === null || n === undefined || Number.isNaN(n) ? '—' : n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits })

export const fmtMs = (n: number | null | undefined) => (n === null || n === undefined ? '—' : `${fmtNum(n)} ms`)

export const pct = (part: number, total: number) => (total > 0 ? (100 * part) / total : 0)
