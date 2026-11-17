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
          'inline-flex items-center justify-center font-medium rounded-md transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500/40 focus:ring-offset-1 disabled:opacity-50 disabled:pointer-events-none select-none',
          // Variants
          variant === 'primary' && 'bg-primary-600 text-white hover:bg-primary-700 active:bg-primary-800 shadow-sm',
          variant === 'secondary' && 'bg-slate-100 text-slate-800 hover:bg-slate-200 active:bg-slate-300',
          variant === 'outline' && 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 active:bg-slate-100 shadow-xs',
          variant === 'danger' && 'bg-rose-600 text-white hover:bg-rose-700 active:bg-rose-800 shadow-sm',
          variant === 'ghost' && 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
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
