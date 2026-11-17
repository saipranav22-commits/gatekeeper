import React from 'react'
import { Menu, LogOut, ShieldCheck, Activity } from 'lucide-react'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/Button'

interface HeaderProps {
  onOpenMobile: () => void
}

export const Header: React.FC<HeaderProps> = ({ onOpenMobile }) => {
  const { admin, logout } = useAuth()

  return (
    <header className="h-16 bg-white border-b border-slate-200/80 px-4 sm:px-6 flex items-center justify-between shrink-0 sticky top-0 z-30 shadow-xs">
      <div className="flex items-center gap-3">
        <button
          onClick={onOpenMobile}
          className="lg:hidden p-2 rounded-lg text-slate-600 hover:bg-slate-100 transition-colors"
          aria-label="Open navigation menu"
        >
          <Menu className="w-5 h-5" />
        </button>
        <div className="flex items-center gap-2 text-xs font-medium text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200/60">
          <Activity className="w-3.5 h-3.5 text-emerald-600 animate-pulse" />
          <span className="hidden sm:inline">Gateway Telemetry Active</span>
          <span className="sm:hidden">Active</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <div className="hidden md:flex flex-col items-end text-right">
          <span className="text-xs font-semibold text-slate-800">{admin?.username}</span>
          <span className="text-[10px] text-slate-500 font-mono">{admin?.email}</span>
        </div>
        <div className="w-8 h-8 rounded-full bg-indigo-100 text-indigo-700 flex items-center justify-center font-bold text-xs border border-indigo-200">
          <ShieldCheck className="w-4 h-4" />
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={logout}
          className="text-slate-500 hover:text-rose-600 hover:bg-rose-50"
          aria-label="Sign out"
        >
          <LogOut className="w-4 h-4 mr-1 sm:inline hidden" />
          <span className="text-xs">Sign Out</span>
        </Button>
      </div>
    </header>
  )
}
