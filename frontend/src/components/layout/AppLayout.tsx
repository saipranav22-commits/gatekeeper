import React, { useState, useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { Header } from './Header'
import { CommandPalette } from '@/components/navigation/CommandPalette'
import { isDemoMode } from '@/config/appMode'

export const AppLayout: React.FC = () => {
  const [mobileOpen, setMobileOpen] = useState<boolean>(false)
  const [commandPaletteOpen, setCommandPaletteOpen] = useState<boolean>(false)

  // Global keyboard shortcut: Cmd+K / Ctrl+K
  useEffect(() => {
    const handleGlobalKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setCommandPaletteOpen((prev) => !prev)
      }
    }
    window.addEventListener('keydown', handleGlobalKeyDown)
    return () => window.removeEventListener('keydown', handleGlobalKeyDown)
  }, [])

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-canvas text-silver-frost antialiased">
      {/* Desktop Sidebar */}
      <div className="hidden lg:flex shrink-0">
        <Sidebar />
      </div>

      {/* Mobile Drawer */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden flex">
          <div
            className="fixed inset-0 bg-black/80 backdrop-blur-sm transition-opacity"
            onClick={() => setMobileOpen(false)}
          />
          <div className="relative flex flex-col w-64 max-w-xs bg-canvas z-10 border-r border-moss/40">
            <Sidebar onCloseMobile={() => setMobileOpen(false)} />
          </div>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden bg-canvas">
        {/* Persistent Recruiter Demo Mode Banner */}
        {isDemoMode() && (
          <div className="bg-surface-elevated/90 border-b border-moss/40 px-4 py-2 flex flex-wrap items-center justify-between text-xs font-mono text-titanium gap-2 shrink-0">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 rounded bg-emerald/20 text-emerald font-semibold border border-emerald/30 uppercase tracking-wider text-[10px]">
                Interactive Demo
              </span>
              <span>Demonstration dataset & simulated policy engine. No live backend required.</span>
            </div>
            <div className="hidden md:flex items-center gap-2 text-titanium/80">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald animate-pulse" />
              <span>Real Spring Boot 3.3.5 / Redis engine verified locally</span>
            </div>
          </div>
        )}

        <Header
          onOpenMobile={() => setMobileOpen(true)}
          onOpenCommandPalette={() => setCommandPaletteOpen(true)}
        />
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 bg-canvas">
          <div className="max-w-7xl mx-auto space-y-6">
            <Outlet />
          </div>
        </main>
      </div>

      {/* Global Command Palette */}
      <CommandPalette
        isOpen={commandPaletteOpen}
        onClose={() => setCommandPaletteOpen(false)}
      />
    </div>
  )
}
