import React from 'react'
import { cn } from '@/lib/utils'

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
  helperText?: string
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type = 'text', label, error, helperText, id, ...props }, ref) => {
    const inputId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined)

    return (
      <div className="w-full flex flex-col gap-1.5 text-left">
        {label && (
          <label htmlFor={inputId} className="text-xs font-semibold uppercase tracking-wider text-titanium">
            {label}
          </label>
        )}
        <input
          id={inputId}
          type={type}
          ref={ref}
          className={cn(
            'w-full px-3.5 py-2 text-sm bg-input border rounded-md transition-all placeholder:text-titanium-muted text-silver-frost focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-canvas',
            error
              ? 'border-signal-red/60 text-silver-frost focus:border-signal-red focus:ring-signal-red/40'
              : 'border-moss/60 text-silver-frost focus:border-emerald focus:ring-emerald/40',
            className
          )}
          {...props}
        />
        {error && <p className="text-xs text-signal-red font-medium">{error}</p>}
        {!error && helperText && <p className="text-xs text-titanium">{helperText}</p>}
      </div>
    )
  }
)
Input.displayName = 'Input'
