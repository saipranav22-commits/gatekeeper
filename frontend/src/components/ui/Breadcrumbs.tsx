import React from 'react'
import { Link } from 'react-router-dom'
import { ChevronRight, Home } from 'lucide-react'

export interface BreadcrumbItem {
  label: string
  href?: string
}

export interface BreadcrumbsProps {
  items: BreadcrumbItem[]
}

export const Breadcrumbs: React.FC<BreadcrumbsProps> = ({ items }) => {
  return (
    <nav className="flex items-center text-xs text-titanium font-medium" aria-label="Breadcrumb">
      <Link to="/" className="flex items-center gap-1.5 hover:text-emerald transition-colors text-titanium">
        <Home className="w-3.5 h-3.5" />
        <span>Control Plane</span>
      </Link>
      {items.map((item, index) => (
        <React.Fragment key={index}>
          <ChevronRight className="w-3.5 h-3.5 mx-1.5 text-titanium-muted shrink-0" />
          {item.href ? (
            <Link to={item.href} className="hover:text-emerald transition-colors text-titanium">
              {item.label}
            </Link>
          ) : (
            <span className="text-silver-frost font-semibold">{item.label}</span>
          )}
        </React.Fragment>
      ))}
    </nav>
  )
}
