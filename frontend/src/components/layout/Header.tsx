import React from 'react'
import { Menu, LogOut, ShieldCheck, Search } from 'lucide-react'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/Button'

interface HeaderProps {
  onOpenMobile: () => void
  onOpenCommandPalette?: () => void
}

export const Header: React.FC<HeaderProps> = ({ onOpenMobile, onOpenCommandPalette }) => {
  const { admin, logout } = useAuth()

  return (
    <header className="h-16 bg-surface/80 backdrop-blur-md border-b border-moss/40 px-4 sm:px-6 flex items-center justify-between shrink-0 sticky top-0 z-30 shadow-card-dark">
      {/* Left side: Mobile menu toggle + Telemetry status */}
      <div className="flex items-center gap-3">
        <button
          onClick={onOpenMobile}
          className="lg:hidden p-2 rounded-lg text-titanium hover:text-silver-frost hover:bg-surface-elevated transition-colors cursor-pointer"
          aria-label="Open navigation menu"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Real-time telemetry beacon */}
        <div className="flex items-center gap-2 text-xs font-mono font-medium text-emerald bg-emerald/10 px-3 py-1 rounded-full border border-emerald/30 shadow-xs">
          <span className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald"></span>
          </span>
          <span className="hidden sm:inline">Gateway Telemetry Active</span>
          <span className="sm:hidden">Active</span>
        </div>
      </div>

      {/* Middle: Command Palette trigger */}
      <div className="flex-1 max-w-md mx-4 hidden md:block">
        <button
          type="button"
          onClick={onOpenCommandPalette}
          className="w-full flex items-center justify-between px-3 py-1.5 rounded-lg bg-surface-elevated/70 border border-moss-subtle text-xs text-titanium hover:text-silver-frost hover:border-emerald/50 hover:bg-surface-elevated transition-all cursor-pointer group shadow-xs"
          aria-label="Open command palette"
        >
          <div className="flex items-center gap-2">
            <Search className="w-3.5 h-3.5 text-titanium-muted group-hover:text-emerald transition-colors" />
            <span>Search commands, screens, or actions...</span>
          </div>
          <div className="flex items-center gap-1">
            <kbd className="px-1.5 py-0.5 text-[10px] font-mono bg-surface rounded border border-moss-subtle text-titanium group-hover:text-silver-frost">
              Ctrl+K
            </kbd>
          </div>
        </button>
      </div>

      {/* Right side: Mobile command palette icon + Operator profile + Sign out */}
      <div className="flex items-center gap-2 sm:gap-3">
        <button
          type="button"
          onClick={onOpenCommandPalette}
          className="md:hidden p-2 rounded-lg text-titanium hover:text-silver-frost hover:bg-surface-elevated transition-colors cursor-pointer"
          aria-label="Search"
        >
          <Search className="w-4 h-4" />
        </button>

        <div className="hidden sm:flex flex-col items-end text-right">
          <span className="text-xs font-semibold text-silver-frost">{admin?.username || 'Operator'}</span>
          <span className="text-[10px] text-titanium font-mono">{admin?.email || 'admin@gatekeeper.local'}</span>
        </div>

        <div className="w-8 h-8 rounded-lg bg-moss/30 text-emerald flex items-center justify-center font-bold text-xs border border-emerald/30 shadow-xs">
          <ShieldCheck className="w-4 h-4" />
        </div>

        <Button
          variant="ghost"
          size="sm"
          onClick={logout}
          className="text-titanium hover:text-signal-red hover:bg-signal-red/10 cursor-pointer"
          aria-label="Sign out"
        >
          <LogOut className="w-4 h-4 mr-1 sm:inline hidden" />
          <span className="text-xs">Sign Out</span>
        </Button>
      </div>
    </header>
  )
}
