import React from 'react'
import { NavLink } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import {
  LayoutDashboard,
  Users,
  KeyRound,
  Layers,
  BarChart3,
  Shield,
  LogOut,
  ShieldCheck,
  X,
} from 'lucide-react'
import { cn } from '@/lib/utils'

interface SidebarProps {
  onCloseMobile?: () => void
}

export const Sidebar: React.FC<SidebarProps> = ({ onCloseMobile }) => {
  const { admin, logout } = useAuth()

  const navItems = [
    { label: 'Overview', to: '/', icon: LayoutDashboard },
    { label: 'Consumers', to: '/consumers', icon: Users },
    { label: 'API Keys', to: '/api-keys', icon: KeyRound },
    { label: 'Subscription Plans', to: '/plans', icon: Layers },
    { label: 'Usage Analytics', to: '/analytics', icon: BarChart3 },
    { label: 'Security & Account', to: '/security', icon: Shield },
  ]

  const formatRole = (role: string | undefined) => {
    if (!role) return ''
    return role.replace('ROLE_', '').replace('_', ' ')
  }

  return (
    <aside className="w-64 bg-slate-950 text-slate-300 flex flex-col h-full border-r border-slate-800 select-none">
      {/* Brand Header */}
      <div className="h-16 px-6 flex items-center justify-between border-b border-slate-800/80">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-indigo-500 to-primary-700 flex items-center justify-center text-white shadow-md shadow-indigo-500/20">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <h1 className="font-bold text-white text-base tracking-tight leading-none">Gatekeeper</h1>
            <span className="text-[10px] text-slate-400 font-medium uppercase tracking-wider">Admin Gateway</span>
          </div>
        </div>
        {onCloseMobile && (
          <button
            onClick={onCloseMobile}
            className="lg:hidden p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800"
            aria-label="Close menu"
          >
            <X className="w-5 h-5" />
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-6 space-y-1 overflow-y-auto">
        <div className="px-3 mb-2 text-[10px] font-bold text-slate-400 uppercase tracking-widest">
          Platform
        </div>
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            onClick={onCloseMobile}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-sm font-medium transition-colors',
                isActive
                  ? 'bg-indigo-600/20 text-indigo-400 font-semibold border-l-2 border-indigo-500 pl-3'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/80'
              )
            }
          >
            <item.icon className="w-4 h-4 shrink-0" />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>

      {/* Role / User Profile Card */}
      <div className="p-4 border-t border-slate-800/80 bg-slate-900/40">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-slate-400">Signed in as</span>
          <span className="px-2 py-0.5 text-[10px] font-semibold tracking-wider rounded-full bg-indigo-950 text-indigo-300 border border-indigo-800/60 uppercase">
            {formatRole(admin?.role)}
          </span>
        </div>
        <div className="flex items-center justify-between">
          <div className="truncate mr-2">
            <p className="text-xs font-semibold text-white truncate">{admin?.username || 'Operator'}</p>
            <p className="text-[11px] text-slate-400 truncate">{admin?.email || 'admin@gatekeeper.local'}</p>
          </div>
          <button
            onClick={logout}
            className="p-1.5 rounded-md text-slate-400 hover:text-rose-400 hover:bg-rose-950/30 transition-colors shrink-0"
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
