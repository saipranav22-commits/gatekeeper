import React from 'react'
import { cn } from '@/lib/utils'

export interface SelectOption {
  label: string
  value: string
  disabled?: boolean
}

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string
  options?: SelectOption[]
  error?: string
  helperText?: string
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, label, options = [], error, helperText, id, children, ...props }, ref) => {
    const selectId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined)

    return (
      <div className="w-full flex flex-col gap-1.5 text-left">
        {label && (
          <label htmlFor={selectId} className="text-xs font-semibold uppercase tracking-wider text-titanium">
            {label}
          </label>
        )}
        <select
          id={selectId}
          ref={ref}
          className={cn(
            'w-full px-3.5 py-2 text-sm bg-input border rounded-md transition-all focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-canvas text-silver-frost',
            error
              ? 'border-signal-red/60 focus:border-signal-red focus:ring-signal-red/40'
              : 'border-moss/60 focus:border-emerald focus:ring-emerald/40',
            className
          )}
          {...props}
        >
          {children
            ? children
            : options.map((opt) => (
                <option key={opt.value} value={opt.value} disabled={opt.disabled} className="bg-surface text-silver-frost">
                  {opt.label}
                </option>
              ))}
        </select>
        {error && <p className="text-xs text-signal-red font-medium">{error}</p>}
        {!error && helperText && <p className="text-xs text-titanium">{helperText}</p>}
      </div>
    )
  }
)
Select.displayName = 'Select'
