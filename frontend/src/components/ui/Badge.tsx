import React from 'react'
import { cn } from '@/lib/utils'

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info' | 'purple' | 'slate' | 'recovering'
}

export const Badge: React.FC<BadgeProps> = ({ className, variant = 'default', children, ...props }) => {
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold uppercase tracking-wider tabular-nums transition-colors',
        variant === 'default' && 'bg-surface-elevated text-titanium border border-moss-subtle',
        variant === 'success' && 'bg-emerald/10 text-emerald border border-emerald/30 shadow-xs',
        variant === 'warning' && 'bg-warning-amber/10 text-warning-amber border border-warning-amber/30',
        variant === 'danger' && 'bg-signal-red/10 text-signal-red border border-signal-red/30',
        variant === 'recovering' && 'bg-sky-500/15 text-sky-400 border border-sky-500/30 shadow-xs',
        variant === 'info' && 'bg-mint/10 text-mint border border-mint/30',
        variant === 'purple' && 'bg-purple-950/40 text-purple-300 border border-purple-800/40',
        variant === 'slate' && 'bg-surface text-silver-frost border border-moss',
        className
      )}
      {...props}
    >
      {children}
    </span>
  )
}
