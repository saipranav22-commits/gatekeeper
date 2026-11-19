import React from 'react'
import { NavLink } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { useGatewayHealth } from '@/context/GatewayHealthContext'
import {
  LayoutDashboard,
  Users,
  KeyRound,
  Layers,
  BarChart3,
  Shield,
  LogOut,
  ShieldCheck,
  Sliders,
  X,
} from 'lucide-react'
import { cn } from '@/lib/utils'

interface SidebarProps {
  onCloseMobile?: () => void
}

export const Sidebar: React.FC<SidebarProps> = ({ onCloseMobile }) => {
  const { admin, logout } = useAuth()
  const { status, healthData, lastChecked } = useGatewayHealth()

  const navSections = [
    {
      heading: 'Command & Control',
      items: [
        { label: 'Overview', to: '/', icon: LayoutDashboard },
      ],
    },
    {
      heading: 'API Management',
      items: [
        { label: 'Consumers', to: '/consumers', icon: Users },
        { label: 'API Keys', to: '/api-keys', icon: KeyRound },
        { label: 'Subscription Plans', to: '/plans', icon: Layers },
        { label: 'Policy Studio', to: '/policies', icon: Sliders },
      ],
    },
    {
      heading: 'Observability & Trust',
      items: [
        { label: 'Usage Analytics', to: '/analytics', icon: BarChart3 },
        { label: 'Security & Account', to: '/security', icon: Shield },
      ],
    },
  ]

  const formatRole = (role: string | undefined) => {
    if (!role) return 'OPERATOR'
    return role.replace('ROLE_', '').replace('_', ' ')
  }

  return (
    <aside className="w-64 bg-canvas text-titanium flex flex-col h-full border-r border-moss/40 select-none">
      {/* Brand Header */}
      <div className="h-16 px-6 flex items-center justify-between border-b border-moss/40 bg-surface/40">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-emerald/10 border border-emerald/30 flex items-center justify-center text-emerald shadow-emerald-sm">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <h1 className="font-bold text-silver-frost text-base tracking-tight leading-none flex items-center gap-1.5">
              Gatekeeper
            </h1>
            <span className="text-[10px] text-emerald font-mono font-medium uppercase tracking-widest">
              Control Plane
            </span>
          </div>
        </div>
        {onCloseMobile && (
          <button
            onClick={onCloseMobile}
            className="lg:hidden p-1.5 rounded-md text-titanium hover:text-silver-frost hover:bg-surface-hover cursor-pointer"
            aria-label="Close menu"
          >
            <X className="w-5 h-5" />
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-5 space-y-6 overflow-y-auto">
        {navSections.map((section) => (
          <div key={section.heading} className="space-y-1">
            <div className="px-3 mb-1 text-[10px] font-bold text-titanium-muted uppercase tracking-widest font-mono">
              {section.heading}
            </div>
            {section.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                onClick={onCloseMobile}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-xs font-medium transition-all duration-150',
                    isActive
                      ? 'bg-emerald/10 text-emerald font-semibold border-l-2 border-emerald pl-3 shadow-xs'
                      : 'text-titanium hover:text-silver-frost hover:bg-surface-elevated/60'
                  )
                }
              >
                <item.icon className="w-4 h-4 shrink-0" />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      {/* Real System Status Indicator */}
      <div
        className="px-4 py-2 mx-3 mb-3 bg-surface-elevated/50 border border-moss-subtle rounded-lg flex items-center justify-between text-[11px]"
        title={lastChecked ? `Status verified: ${lastChecked.toLocaleTimeString()}` : 'Verifying status'}
      >
        <div className="flex items-center gap-2">
          <span
            className={cn(
              'w-2 h-2 rounded-full',
              status === 'HEALTHY' && 'bg-emerald',
              status === 'DEGRADED' && 'bg-warning-amber',
              status === 'OFFLINE' && 'bg-signal-red',
              (status === 'CHECKING' || status === 'STALE') && 'bg-titanium'
            )}
          />
          <span className="text-silver-frost font-medium font-mono">
            {status === 'HEALTHY' && 'Gateway Online'}
            {status === 'DEGRADED' && 'Circuit Degraded'}
            {status === 'OFFLINE' && 'Gateway Offline'}
            {status === 'CHECKING' && 'Checking Engine...'}
            {status === 'STALE' && 'Status Stale'}
          </span>
        </div>
        <span className="text-titanium font-mono text-[10px]">
          {healthData?.serviceName || 'v1.0'}
        </span>
      </div>

      {/* Role / User Profile Card */}
      <div className="p-4 border-t border-moss/40 bg-surface/30">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-titanium">Signed in as</span>
          <span className="px-2 py-0.5 text-[10px] font-mono font-semibold tracking-wider rounded-full bg-moss/30 text-emerald border border-emerald/30 uppercase">
            {formatRole(admin?.role)}
          </span>
        </div>
        <div className="flex items-center justify-between">
          <div className="truncate mr-2">
            <p className="text-xs font-semibold text-silver-frost truncate">{admin?.username || 'Operator'}</p>
            <p className="text-[11px] text-titanium truncate font-mono">{admin?.email || 'admin@gatekeeper.local'}</p>
          </div>
          <button
            onClick={logout}
            className="p-1.5 rounded-md text-titanium hover:text-signal-red hover:bg-signal-red/10 transition-colors shrink-0 cursor-pointer"
            title="Sign out"
            aria-label="Sign out"
          >
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </div>
    </aside>
  )
}
