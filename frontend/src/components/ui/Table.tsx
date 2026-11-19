import React from 'react'
import { cn } from '@/lib/utils'

export const Table: React.FC<React.TableHTMLAttributes<HTMLTableElement>> = ({ className, ...props }) => (
  <div className="w-full overflow-x-auto border border-moss/40 rounded-lg bg-surface shadow-card-dark">
    <table className={cn('w-full text-left text-sm text-silver-frost tabular-nums', className)} {...props} />
  </div>
)

export const TableHeader: React.FC<React.HTMLAttributes<HTMLTableSectionElement>> = ({ className, ...props }) => (
  <thead className={cn('bg-surface-elevated/80 border-b border-moss-subtle text-[11px] font-semibold text-titanium uppercase tracking-wider', className)} {...props} />
)

export const TableBody: React.FC<React.HTMLAttributes<HTMLTableSectionElement>> = ({ className, ...props }) => (
  <tbody className={cn('divide-y divide-moss-subtle/50 bg-surface', className)} {...props} />
)

export const TableRow: React.FC<React.HTMLAttributes<HTMLTableRowElement>> = ({ className, ...props }) => (
  <tr className={cn('hover:bg-surface-elevated/60 transition-colors', className)} {...props} />
)

export const TableHead: React.FC<React.ThHTMLAttributes<HTMLTableCellElement>> = ({ className, ...props }) => (
  <th className={cn('px-4 py-3 text-left font-semibold text-titanium', className)} {...props} />
)

export const TableCell: React.FC<React.TdHTMLAttributes<HTMLTableCellElement>> = ({ className, ...props }) => (
  <td className={cn('px-4 py-3 align-middle text-silver-frost', className)} {...props} />
)
