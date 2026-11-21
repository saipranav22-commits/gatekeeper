import React, { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import {
  Search,
  LayoutDashboard,
  Users,
  KeyRound,
  Layers,
  BarChart3,
  Shield,
  LogOut,
  PlusCircle,
  Sliders,
  Network,
  Flame,
} from 'lucide-react'
import { cn } from '@/lib/utils'

export interface CommandItem {
  id: string
  title: string
  description?: string
  category: 'Navigation' | 'Quick Actions' | 'System'
  icon: React.ComponentType<{ className?: string }>
  perform: () => void
  keywords?: string[]
}

interface CommandPaletteProps {
  isOpen: boolean
  onClose: () => void
}

export const CommandPalette: React.FC<CommandPaletteProps> = ({ isOpen, onClose }) => {
  const [query, setQuery] = useState('')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const navigate = useNavigate()
  const { logout, canMutate } = useAuth()
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)

  const commands: CommandItem[] = [
    {
      id: 'nav-overview',
      title: 'Command Center & Overview',
      description: 'Real-time telemetry, token bucket quotas, and health status',
      category: 'Navigation',
      icon: LayoutDashboard,
      keywords: ['dashboard', 'home', 'traffic', 'metrics', 'rps', 'kpi'],
      perform: () => {
        navigate('/')
        onClose()
      },
    },
    {
      id: 'nav-consumers',
      title: 'Consumers & Developers',
      description: 'Manage registered clients, Tier mappings, and rate limit quotas',
      category: 'Navigation',
      icon: Users,
      keywords: ['clients', 'users', 'developers', 'tenants', 'tiers'],
      perform: () => {
        navigate('/consumers')
        onClose()
      },
    },
    {
      id: 'nav-apikeys',
      title: 'API Keys & Secrets',
      description: 'Cryptographic credentials, prefixes, and SHA-256 revocation',
      category: 'Navigation',
      icon: KeyRound,
      keywords: ['credentials', 'tokens', 'secrets', 'hash', 'revoke'],
      perform: () => {
        navigate('/api-keys')
        onClose()
      },
    },
    {
      id: 'nav-plans',
      title: 'Subscription Plans',
      description: 'Token bucket refill rates, capacity limits, and tier policies',
      category: 'Navigation',
      icon: Layers,
      keywords: ['tiers', 'limits', 'rate limits', 'refill', 'burst'],
      perform: () => {
        navigate('/plans')
        onClose()
      },
    },
    {
      id: 'nav-analytics',
      title: 'Usage Analytics & Telemetry',
      description: 'Granular latency histograms, status codes, and throughput trends',
      category: 'Navigation',
      icon: BarChart3,
      keywords: ['graphs', 'charts', 'latency', '429', 'status codes', 'history'],
      perform: () => {
        navigate('/analytics')
        onClose()
      },
    },
    {
      id: 'nav-policies',
      title: 'Policy Studio & Rule Engine',
      description: 'Versioned ingress rules, dry-run simulations, and historical replay',
      category: 'Navigation',
      icon: Sliders,
      keywords: ['policy', 'rules', 'simulate', 'decision', 'dry run', 'replay', 'governance'],
      perform: () => {
        navigate('/policies')
        onClose()
      },
    },
    {
      id: 'nav-reliability',
      title: 'Reliability & Service Topology',
      description: 'Configured topology, circuit breakers, bulkhead permits, and operational events',
      category: 'Navigation',
      icon: Network,
      keywords: ['topology', 'resilience', 'circuit breaker', 'bulkhead', 'retry', 'health', 'timeline', 'sre'],
      perform: () => {
        navigate('/reliability')
        onClose()
      },
    },
    {
      id: 'nav-chaos',
      title: 'Chaos Engineering Laboratory',
      description: 'Controlled resilience scenarios, failure injection, and Phase 5D benchmark evidence',
      category: 'Navigation',
      icon: Flame,
      keywords: ['chaos', 'failure injection', 'scenario', 'benchmarks', 'experiment', 'orders outage', 'redis failure'],
      perform: () => {
        navigate('/chaos')
        onClose()
      },
    },
    {
      id: 'nav-security',
      title: 'Security & Access Control',
      description: 'Admin credentials, active sessions, and gateway security parameters',
      category: 'Navigation',
      icon: Shield,
      keywords: ['auth', 'admin', 'password', 'jwt', 'rbac'],
      perform: () => {
        navigate('/security')
        onClose()
      },
    },
    ...(canMutate()
      ? [
          {
            id: 'action-register-consumer',
            title: 'Register New Consumer',
            description: 'Provision a new client tenant with dedicated subscription tier',
            category: 'Quick Actions' as const,
            icon: PlusCircle,
            keywords: ['create consumer', 'new client', 'add developer'],
            perform: () => {
              navigate('/consumers?action=register')
              onClose()
            },
          },
          {
            id: 'action-create-key',
            title: 'Generate API Key',
            description: 'Create an encrypted API key for an active consumer',
            category: 'Quick Actions' as const,
            icon: KeyRound,
            keywords: ['new key', 'create token', 'generate credential'],
            perform: () => {
              navigate('/api-keys?action=create')
              onClose()
            },
          },
          {
            id: 'action-policy-studio',
            title: 'Open Policy Studio',
            description: 'Inspect active rules and execute dry-run ingress simulations',
            category: 'Quick Actions' as const,
            icon: Sliders,
            keywords: ['simulate rule', 'dry run', 'inspect policy', 'test route'],
            perform: () => {
              navigate('/policies')
              onClose()
            },
          },
          {
            id: 'action-view-topology',
            title: 'Inspect Service Topology',
            description: 'View real-time dependency status and circuit breaker overlays',
            category: 'Quick Actions' as const,
            icon: Network,
            keywords: ['topology', 'dependency graph', 'service map', 'upstream'],
            perform: () => {
              navigate('/reliability?tab=topology')
              onClose()
            },
          },
          {
            id: 'action-view-chaos',
            title: 'Review Chaos Benchmarks',
            description: 'Explore Phase 5D resilience benchmark evidence and assertions',
            category: 'Quick Actions' as const,
            icon: Flame,
            keywords: ['chaos', 'failure injection', 'benchmarks', 'resilience test'],
            perform: () => {
              navigate('/chaos')
              onClose()
            },
          },
        ]
      : []),
    {
      id: 'action-signout',
      title: 'Sign Out of Gateway',
      description: 'Purge volatile in-memory JWT tokens and lock control plane',
      category: 'System',
      icon: LogOut,
      keywords: ['logout', 'exit', 'lock'],
      perform: () => {
        logout()
        onClose()
      },
    },
  ]

  // Filter commands by search query
  const filteredCommands = commands.filter((cmd) => {
    if (!query.trim()) return true
    const q = query.toLowerCase()
    return (
      cmd.title.toLowerCase().includes(q) ||
      (cmd.description && cmd.description.toLowerCase().includes(q)) ||
      (cmd.keywords && cmd.keywords.some((k) => k.toLowerCase().includes(q)))
    )
  })

  // Keyboard navigation within palette
  useEffect(() => {
    if (isOpen) {
      const timer = setTimeout(() => inputRef.current?.focus(), 50)
      return () => clearTimeout(timer)
    }
  }, [isOpen])

  const handleClose = useCallback(() => {
    setQuery('')
    setSelectedIndex(0)
    onClose()
  }, [onClose])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!isOpen) return

      if (e.key === 'Escape') {
        e.preventDefault()
        handleClose()
      } else if (e.key === 'ArrowDown') {
        e.preventDefault()
        setSelectedIndex((prev) => (prev < filteredCommands.length - 1 ? prev + 1 : 0))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setSelectedIndex((prev) => (prev > 0 ? prev - 1 : filteredCommands.length - 1))
      } else if (e.key === 'Enter') {
        e.preventDefault()
        if (filteredCommands[selectedIndex]) {
          const action = filteredCommands[selectedIndex]
          setQuery('')
          setSelectedIndex(0)
          action.perform()
        }
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, filteredCommands, selectedIndex, handleClose])

  if (!isOpen) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-20 p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150"
      onClick={handleClose}
      role="dialog"
      aria-modal="true"
      aria-label="Command Palette"
    >
      <div
        className="w-full max-w-xl bg-surface rounded-xl shadow-modal-dark border border-moss/60 overflow-hidden transform animate-in zoom-in-95 duration-150"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Search Input Bar */}
        <div className="flex items-center px-4 py-3.5 border-b border-moss-subtle bg-surface-elevated/50">
          <Search className="w-5 h-5 text-titanium shrink-0 mr-3" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setSelectedIndex(0)
            }}
            placeholder="Search commands, screens, or actions... (e.g. 'consumers', 'key', 'logout')"
            className="w-full bg-transparent text-silver-frost placeholder:text-titanium-muted text-sm focus:outline-none"
            aria-label="Search commands"
          />
          <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-mono font-medium text-titanium bg-surface border border-moss-subtle rounded">
            ESC
          </kbd>
        </div>

        {/* Results List */}
        <div ref={listRef} className="max-h-80 overflow-y-auto p-2 divide-y divide-moss-subtle/30">
          {filteredCommands.length === 0 ? (
            <div className="py-8 text-center text-xs text-titanium">
              No matching commands or routes found for <span className="text-silver-frost font-mono">"{query}"</span>
            </div>
          ) : (
            <div className="space-y-1">
              {filteredCommands.map((cmd, idx) => {
                const isSelected = idx === selectedIndex
                const Icon = cmd.icon

                return (
                  <div
                    key={cmd.id}
                    onClick={() => cmd.perform()}
                    onMouseEnter={() => setSelectedIndex(idx)}
                    className={cn(
                      'flex items-center justify-between px-3 py-2.5 rounded-lg text-xs cursor-pointer transition-colors',
                      isSelected
                        ? 'bg-emerald/10 text-emerald border border-emerald/30'
                        : 'text-silver-frost hover:bg-surface-elevated/80 border border-transparent'
                    )}
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <div
                        className={cn(
                          'p-1.5 rounded-md shrink-0',
                          isSelected ? 'bg-emerald/20 text-emerald' : 'bg-surface-elevated text-titanium'
                        )}
                      >
                        <Icon className="w-4 h-4" />
                      </div>
                      <div className="truncate">
                        <div className="font-medium text-silver-frost truncate">{cmd.title}</div>
                        {cmd.description && (
                          <div className="text-[11px] text-titanium truncate">{cmd.description}</div>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 ml-3">
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-surface border border-moss-subtle text-titanium uppercase tracking-wider font-semibold">
                        {cmd.category}
                      </span>
                      {isSelected && (
                        <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-mono text-emerald bg-emerald/20 border border-emerald/40 rounded">
                          ↵
                        </kbd>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* Footer shortcuts */}
        <div className="px-4 py-2 bg-surface-elevated/40 border-t border-moss-subtle text-[11px] text-titanium flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span>Navigate:</span>
            <kbd className="px-1 py-0.5 font-mono text-[10px] bg-surface rounded border border-moss-subtle">↑</kbd>
            <kbd className="px-1 py-0.5 font-mono text-[10px] bg-surface rounded border border-moss-subtle">↓</kbd>
            <span className="ml-2">Select:</span>
            <kbd className="px-1 py-0.5 font-mono text-[10px] bg-surface rounded border border-moss-subtle">↵</kbd>
          </div>
          <span className="font-mono text-[10px] text-emerald">Gatekeeper v1.0 • Control Plane</span>
        </div>
      </div>
    </div>
  )
}
