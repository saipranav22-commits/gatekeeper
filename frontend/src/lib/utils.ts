import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatNumber(num: number | undefined | null): string {
  if (num === undefined || num === null || isNaN(num)) return '0'
  return new Intl.NumberFormat('en-US').format(num)
}

export function formatPercentage(val: number | undefined | null): string {
  if (val === undefined || val === null || isNaN(val)) return '0.0%'
  return `${val.toFixed(1)}%`
}

export function formatDate(isoString: string | undefined | null): string {
  if (!isoString) return '—'
  try {
    const d = new Date(isoString)
    if (isNaN(d.getTime())) return '—'
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(d)
  } catch {
    return '—'
  }
}

export function formatDateUtc(isoString: string | undefined | null): string {
  if (!isoString) return '—'
  try {
    const d = new Date(isoString)
    if (isNaN(d.getTime())) return '—'
    return d.toISOString().replace('T', ' ').substring(0, 19) + ' UTC'
  } catch {
    return '—'
  }
}

export function truncate(str: string | undefined | null, length: number = 16): string {
  if (!str) return '—'
  if (str.length <= length) return str
  return `${str.substring(0, length)}...`
}

export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null || isNaN(ms)) return '0 ms'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

export function getClientTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Local'
  } catch {
    return 'Local'
  }
}
