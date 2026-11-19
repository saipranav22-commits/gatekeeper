import React from 'react'
import { cn } from '@/lib/utils'

export const Skeleton: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({ className, ...props }) => {
  return <div className={cn('animate-pulse rounded-md bg-surface-elevated/80 border border-moss-subtle/40', className)} {...props} />
}
