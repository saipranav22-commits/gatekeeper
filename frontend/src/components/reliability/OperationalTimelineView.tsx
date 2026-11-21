import React, { useState } from 'react'
import {
  OperationalTimelineEvent,
  OperationalEventSeverity,
} from '@/types/resilience'
import { Badge } from '@/components/ui/Badge'
import {
  Activity,
  Info,
  Filter,
  Search,
  Copy,
  Check,
  Calendar,
} from 'lucide-react'

interface OperationalTimelineViewProps {
  events: OperationalTimelineEvent[]
}

export const OperationalTimelineView: React.FC<OperationalTimelineViewProps> = ({ events }) => {
  const [selectedSeverity, setSelectedSeverity] = useState<string>('ALL')
  const [selectedCategory, setSelectedCategory] = useState<string>('ALL')
  const [searchQuery, setSearchQuery] = useState<string>('')
  const [copiedId, setCopiedId] = useState<string | null>(null)

  const handleCopy = (id: string, text: string) => {
    navigator.clipboard.writeText(text)
    setCopiedId(id)
    setTimeout(() => setCopiedId(null), 2000)
  }

  const getSeverityBadge = (severity: OperationalEventSeverity) => {
    switch (severity) {
      case 'CRITICAL':
        return <Badge variant="danger">CRITICAL</Badge>
      case 'WARNING':
        return <Badge variant="warning">WARNING</Badge>
      case 'RECOVERY':
        return <Badge variant="success">RECOVERY</Badge>
      case 'INFO':
      default:
        return <Badge variant="slate">INFO</Badge>
    }
  }

  const filteredEvents = events.filter((evt) => {
    if (selectedSeverity !== 'ALL' && evt.severity !== selectedSeverity) return false
    if (selectedCategory !== 'ALL' && evt.category !== selectedCategory) return false
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      const matchHeadline = evt.headline.toLowerCase().includes(q)
      const matchDetails = evt.details.toLowerCase().includes(q)
      const matchCorr = evt.correlationId?.toLowerCase().includes(q)
      const matchRoute = evt.routeId?.toLowerCase().includes(q)
      return matchHeadline || matchDetails || matchCorr || matchRoute
    }
    return true
  })

  return (
    <div className="space-y-4">
      {/* Filter and Search Bar */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 bg-surface border border-moss/30 p-4 rounded-xl">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 text-titanium text-xs font-mono mr-2">
            <Filter className="w-3.5 h-3.5 text-emerald" />
            <span>Filters:</span>
          </div>

          {/* Severity Dropdown */}
          <select
            value={selectedSeverity}
            onChange={(e) => setSelectedSeverity(e.target.value)}
            className="bg-canvas border border-moss/40 text-silver-frost text-xs font-mono px-3 py-1.5 rounded-lg focus:border-emerald focus:outline-none"
          >
            <option value="ALL">All Severities</option>
            <option value="CRITICAL">Critical Only</option>
            <option value="WARNING">Warning Only</option>
            <option value="RECOVERY">Recovery Only</option>
            <option value="INFO">Info Only</option>
          </select>

          {/* Category Dropdown */}
          <select
            value={selectedCategory}
            onChange={(e) => setSelectedCategory(e.target.value)}
            className="bg-canvas border border-moss/40 text-silver-frost text-xs font-mono px-3 py-1.5 rounded-lg focus:border-emerald focus:outline-none"
          >
            <option value="ALL">All Categories</option>
            <option value="CIRCUIT_BREAKER">Circuit Breaker</option>
            <option value="BULKHEAD">Bulkhead</option>
            <option value="UPSTREAM_ERROR">Upstream Error</option>
            <option value="POLICY_DENIAL">Policy Denial</option>
            <option value="CHAOS_EXPERIMENT">Chaos Experiment</option>
          </select>
        </div>

        {/* Search Input */}
        <div className="relative min-w-[220px]">
          <Search className="w-3.5 h-3.5 text-titanium absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search headline, route, correlation ID..."
            className="w-full bg-canvas border border-moss/40 text-silver-frost placeholder-titanium/60 text-xs font-mono pl-8 pr-3 py-1.5 rounded-lg focus:border-emerald focus:outline-none"
          />
        </div>
      </div>

      {/* Events Timeline List */}
      <div className="space-y-3">
        {filteredEvents.length === 0 ? (
          <div className="bg-surface/50 border border-moss/20 rounded-xl p-8 text-center space-y-2">
            <Activity className="w-6 h-6 text-titanium mx-auto" />
            <p className="text-xs font-mono text-titanium">No operational events match the active filters.</p>
          </div>
        ) : (
          filteredEvents.map((evt) => (
            <div
              key={evt.id}
              className="bg-surface border border-moss/30 hover:border-moss/60 rounded-xl p-4 transition-colors space-y-3"
            >
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-moss/20 pb-2.5">
                <div className="flex items-center gap-2.5 flex-wrap">
                  {getSeverityBadge(evt.severity)}
                  <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-moss/20 text-titanium border border-moss/30">
                    {evt.category}
                  </span>
                  <span className="font-mono font-bold text-silver-frost text-sm">
                    {evt.headline}
                  </span>
                </div>
                <div className="flex items-center gap-2 text-titanium font-mono text-xs">
                  <Calendar className="w-3.5 h-3.5 text-emerald" />
                  <span>{new Date(evt.timestamp).toLocaleString()}</span>
                </div>
              </div>

              {/* Event Details */}
              <div className="space-y-1.5 text-xs font-mono">
                <p className="text-silver-frost leading-relaxed">{evt.details}</p>

                {/* Explicit Derived Severity Rule */}
                <div className="text-[11px] text-titanium flex items-center gap-1.5 pt-1">
                  <Info className="w-3 h-3 text-emerald shrink-0" />
                  <span className="italic">{evt.derivedSeverityRule}</span>
                </div>
              </div>

              {/* Correlation ID & Route Metadata */}
              <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-moss/20 text-[11px] font-mono text-titanium">
                <div className="flex items-center gap-4">
                  <span>Source: <strong className="text-silver-frost">{evt.source}</strong></span>
                  {evt.routeId && (
                    <span>Route: <strong className="text-emerald">{evt.routeId}</strong></span>
                  )}
                </div>

                {evt.correlationId && (
                  <div className="flex items-center gap-1.5 bg-canvas px-2.5 py-1 rounded border border-moss/30">
                    <span className="text-[10px] text-titanium">Correlation:</span>
                    <span className="text-silver-frost font-bold">{evt.correlationId}</span>
                    <button
                      type="button"
                      onClick={() => handleCopy(evt.id, evt.correlationId!)}
                      className="ml-1 text-titanium hover:text-emerald transition-colors"
                      title="Copy Correlation ID"
                    >
                      {copiedId === evt.id ? (
                        <Check className="w-3 h-3 text-emerald" />
                      ) : (
                        <Copy className="w-3 h-3" />
                      )}
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
