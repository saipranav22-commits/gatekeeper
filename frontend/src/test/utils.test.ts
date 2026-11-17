import { describe, it, expect } from 'vitest'
import {
  cn,
  formatNumber,
  formatPercentage,
  formatDate,
  formatDateUtc,
  truncate,
  getClientTimezone,
} from '@/lib/utils'

describe('Formatting Utilities', () => {
  it('combines class names cleanly with cn()', () => {
    expect(cn('px-4', 'py-2', undefined, 'text-white')).toBe('px-4 py-2 text-white')
    expect(cn('bg-red-500', 'bg-blue-500')).toBe('bg-blue-500') // twMerge precedence
  })

  it('formats numbers with thousand separators', () => {
    expect(formatNumber(1250000)).toBe('1,250,000')
    expect(formatNumber(0)).toBe('0')
    expect(formatNumber(null)).toBe('0')
    expect(formatNumber(undefined)).toBe('0')
  })

  it('formats percentages correctly', () => {
    expect(formatPercentage(15.26)).toBe('15.3%')
    expect(formatPercentage(0)).toBe('0.0%')
    expect(formatPercentage(null)).toBe('0.0%')
  })

  it('formats dates consistently', () => {
    const iso = '2026-11-17T12:00:00Z'
    const formatted = formatDate(iso)
    expect(formatted).not.toBe('—')
    expect(formatDate(null)).toBe('—')
    expect(formatDate('invalid-date')).toBe('—')
  })

  it('formats UTC dates explicitly', () => {
    const iso = '2026-11-17T12:00:00Z'
    const formattedUtc = formatDateUtc(iso)
    expect(formattedUtc).toContain('UTC')
    expect(formattedUtc).toContain('2026-11-17')
    expect(formatDateUtc(null)).toBe('—')
  })

  it('truncates strings when longer than max length', () => {
    expect(truncate('12345678901234567890', 10)).toBe('1234567890...')
    expect(truncate('short', 10)).toBe('short')
    expect(truncate(null)).toBe('—')
  })

  it('retrieves client timezone', () => {
    const tz = getClientTimezone()
    expect(typeof tz).toBe('string')
    expect(tz.length).toBeGreaterThan(0)
  })
})
