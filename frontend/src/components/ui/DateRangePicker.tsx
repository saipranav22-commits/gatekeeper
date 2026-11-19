import React, { useState } from 'react'
import { Calendar, Clock } from 'lucide-react'
import { Button } from './Button'
import { getClientTimezone } from '@/lib/utils'

export type TimeRangePreset = '24h' | '7d' | '30d' | 'custom'

export interface DateRange {
  start: string // ISO string
  end: string   // ISO string
  preset: TimeRangePreset
}

interface DateRangePickerProps {
  range: DateRange
  onChange: (range: DateRange) => void
}

export const DateRangePicker: React.FC<DateRangePickerProps> = ({ range, onChange }) => {
  const [showCustom, setShowCustom] = useState<boolean>(range.preset === 'custom')
  const [customStart, setCustomStart] = useState<string>(() =>
    range.start ? range.start.substring(0, 16) : ''
  )
  const [customEnd, setCustomEnd] = useState<string>(() =>
    range.end ? range.end.substring(0, 16) : ''
  )

  const handlePreset = (preset: TimeRangePreset) => {
    const now = new Date()
    let start: Date

    if (preset === '24h') {
      start = new Date(now.getTime() - 24 * 60 * 60 * 1000)
      setShowCustom(false)
      onChange({ start: start.toISOString(), end: now.toISOString(), preset })
    } else if (preset === '7d') {
      start = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
      setShowCustom(false)
      onChange({ start: start.toISOString(), end: now.toISOString(), preset })
    } else if (preset === '30d') {
      start = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
      setShowCustom(false)
      onChange({ start: start.toISOString(), end: now.toISOString(), preset })
    } else {
      setShowCustom(true)
    }
  }

  const handleApplyCustom = () => {
    try {
      const s = new Date(customStart).toISOString()
      const e = new Date(customEnd).toISOString()
      onChange({ start: s, end: e, preset: 'custom' })
    } catch {
      // Invalid date input
    }
  }

  const timezone = getClientTimezone()

  return (
    <div className="flex flex-col sm:flex-row items-start sm:items-center gap-2">
      <div className="inline-flex rounded-lg border border-moss-subtle bg-surface-elevated p-0.5">
        <button
          type="button"
          onClick={() => handlePreset('24h')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all cursor-pointer ${
            range.preset === '24h'
              ? 'bg-moss/40 text-emerald shadow-xs font-semibold border border-emerald/30'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-hover'
          }`}
        >
          Last 24h
        </button>
        <button
          type="button"
          onClick={() => handlePreset('7d')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all cursor-pointer ${
            range.preset === '7d'
              ? 'bg-moss/40 text-emerald shadow-xs font-semibold border border-emerald/30'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-hover'
          }`}
        >
          Last 7d
        </button>
        <button
          type="button"
          onClick={() => handlePreset('30d')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all cursor-pointer ${
            range.preset === '30d'
              ? 'bg-moss/40 text-emerald shadow-xs font-semibold border border-emerald/30'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-hover'
          }`}
        >
          Last 30d
        </button>
        <button
          type="button"
          onClick={() => handlePreset('custom')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all cursor-pointer ${
            range.preset === 'custom'
              ? 'bg-moss/40 text-emerald shadow-xs font-semibold border border-emerald/30'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-hover'
          }`}
        >
          Custom
        </button>
      </div>

      {showCustom && (
        <div className="flex items-center gap-2 mt-2 sm:mt-0 p-1.5 bg-surface border border-moss-subtle rounded-lg text-xs">
          <Calendar className="w-3.5 h-3.5 text-titanium ml-1" />
          <input
            type="datetime-local"
            value={customStart}
            onChange={(e) => setCustomStart(e.target.value)}
            className="px-2 py-1 bg-input border border-moss-subtle rounded text-silver-frost text-xs"
          />
          <span className="text-titanium-muted">to</span>
          <input
            type="datetime-local"
            value={customEnd}
            onChange={(e) => setCustomEnd(e.target.value)}
            className="px-2 py-1 bg-input border border-moss-subtle rounded text-silver-frost text-xs"
          />
          <Button size="sm" variant="primary" onClick={handleApplyCustom} className="text-xs py-1 px-2.5">
            Apply
          </Button>
        </div>
      )}

      <div className="flex items-center gap-1 text-[11px] text-titanium pl-1">
        <Clock className="w-3 h-3 text-titanium-muted" />
        <span>{timezone} (UTC synced)</span>
      </div>
    </div>
  )
}
