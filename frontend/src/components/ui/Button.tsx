import React from 'react'
import { cn } from '@/lib/utils'
import { Loader2 } from 'lucide-react'

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'danger' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  isLoading?: boolean
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'primary', size = 'md', isLoading = false, children, disabled, ...props }, ref) => {
    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={cn(
          'inline-flex items-center justify-center font-medium rounded-md transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-emerald/40 focus:ring-offset-1 focus:ring-offset-canvas disabled:opacity-50 disabled:pointer-events-none select-none cursor-pointer',
          // Variants
          variant === 'primary' &&
            'bg-emerald text-canvas font-semibold hover:bg-emerald-hover active:bg-emerald-active shadow-emerald-sm',
          variant === 'secondary' &&
            'bg-surface-elevated text-silver-frost border border-moss-subtle hover:bg-surface-hover hover:border-moss',
          variant === 'outline' &&
            'border border-moss bg-surface/60 text-silver-frost hover:bg-surface-elevated hover:border-emerald/50 shadow-xs',
          variant === 'danger' &&
            'bg-signal-red text-canvas font-semibold hover:bg-rose-600 active:bg-rose-700 shadow-sm focus:ring-signal-red/40',
          variant === 'ghost' &&
            'text-titanium hover:bg-surface-hover hover:text-silver-frost',
          // Sizes
          size === 'sm' && 'text-xs px-2.5 py-1.5 gap-1.5',
          size === 'md' && 'text-sm px-4 py-2 gap-2',
          size === 'lg' && 'text-base px-5 py-2.5 gap-2.5',
          className
        )}
        {...props}
      >
        {isLoading && <Loader2 className="w-4 h-4 animate-spin shrink-0" />}
        {children}
      </button>
    )
  }
)
Button.displayName = 'Button'
