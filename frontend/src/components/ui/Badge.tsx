import React from 'react'
import { cn } from '@/lib/utils'

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info' | 'purple' | 'slate'
}

export const Badge: React.FC<BadgeProps> = ({ className, variant = 'default', children, ...props }) => {
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold uppercase tracking-wider',
        variant === 'default' && 'bg-slate-100 text-slate-700 border border-slate-200',
        variant === 'success' && 'bg-emerald-50 text-emerald-700 border border-emerald-200/80',
        variant === 'warning' && 'bg-amber-50 text-amber-700 border border-amber-200/80',
        variant === 'danger' && 'bg-rose-50 text-rose-700 border border-rose-200/80',
        variant === 'info' && 'bg-sky-50 text-sky-700 border border-sky-200/80',
        variant === 'purple' && 'bg-indigo-50 text-indigo-700 border border-indigo-200/80',
        variant === 'slate' && 'bg-slate-200 text-slate-800 border border-slate-300',
        className
      )}
      {...props}
    >
      {children}
    </span>
  )
}
