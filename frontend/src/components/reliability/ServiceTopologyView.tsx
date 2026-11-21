import React, { useState } from 'react'
import {
  ConfiguredTopologyData,
} from '@/types/resilience'
import { Badge } from '@/components/ui/Badge'
import {
  Server,
  Database,
  Layers,
  ArrowRight,
  Shield,
  Activity,
  Globe,
  Radio,
  ExternalLink,
  Info,
} from 'lucide-react'

interface ServiceTopologyViewProps {
  topology: ConfiguredTopologyData
  onSelectRoute?: (routeId: string) => void
  onNavigateAnalytics?: (path?: string) => void
}

export const ServiceTopologyView: React.FC<ServiceTopologyViewProps> = ({
  topology,
  onSelectRoute,
  onNavigateAnalytics,
}) => {
  const [selectedNodeId, setSelectedNodeId] = useState<string>('gateway-core')
  const [viewMode, setViewMode] = useState<'diagram' | 'list'>('diagram')

  const selectedNode = topology.nodes.find((n) => n.id === selectedNodeId) || topology.nodes[1]

  const getNodeIcon = (type: string) => {
    switch (type) {
      case 'CLIENT':
        return Globe
      case 'GATEWAY':
        return Shield
      case 'UPSTREAM_HTTP':
        return Server
      case 'DATABASE':
        return Database
      case 'CACHE':
        return Layers
      default:
        return Activity
    }
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return <Badge variant="success">HEALTHY</Badge>
      case 'DEGRADED':
        return <Badge variant="warning">DEGRADED</Badge>
      case 'DOWN':
      case 'UNHEALTHY':
        return <Badge variant="danger">DOWN</Badge>
      case 'RECOVERING':
        return <Badge variant="recovering">RECOVERING</Badge>
      case 'UNKNOWN':
      default:
        return <Badge variant="slate">UNKNOWN</Badge>
    }
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return '#31E99A'
      case 'DEGRADED':
        return '#E9B35A'
      case 'DOWN':
      case 'UNHEALTHY':
        return '#F05460'
      case 'RECOVERING':
        return '#38BDF8'
      case 'UNKNOWN':
      default:
        return '#899693'
    }
  }

  // Pre-calculated node coordinates for crisp, responsive SVG rendering
  // Canvas width: 900, height: 420
  const nodePositions: Record<string, { x: number; y: number }> = {
    'client-ingress': { x: 90, y: 210 },
    'gateway-core': { x: 380, y: 210 },
    'orders-service': { x: 740, y: 100 },
    'inventory-service': { x: 740, y: 210 },
    'postgres-db': { x: 740, y: 310 },
    'redis-cache': { x: 380, y: 360 },
  }

  return (
    <div className="space-y-4">
      {/* Topology Header Controls */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-surface border border-moss/30 p-4 rounded-xl">
        <div className="flex items-center gap-2">
          <Radio className="w-4 h-4 text-emerald animate-pulse" />
          <span className="text-xs font-mono font-medium text-silver-frost">
            Configured Service Topology
          </span>
          <span className="text-[10px] px-2 py-0.5 rounded bg-moss/20 text-titanium border border-moss/30 font-mono">
            Static Declarative Graph
          </span>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex rounded-lg border border-moss/40 p-0.5 bg-canvas">
            <button
              type="button"
              onClick={() => setViewMode('diagram')}
              className={`px-3 py-1 text-xs font-mono rounded-md transition-colors ${
                viewMode === 'diagram'
                  ? 'bg-moss/40 text-emerald font-semibold shadow-xs'
                  : 'text-titanium hover:text-silver-frost'
              }`}
            >
              Interactive Graph
            </button>
            <button
              type="button"
              onClick={() => setViewMode('list')}
              className={`px-3 py-1 text-xs font-mono rounded-md transition-colors ${
                viewMode === 'list'
                  ? 'bg-moss/40 text-emerald font-semibold shadow-xs'
                  : 'text-titanium hover:text-silver-frost'
              }`}
            >
              Node Directory
            </button>
          </div>
        </div>
      </div>

      {viewMode === 'diagram' ? (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {/* SVG Diagram Canvas */}
          <div className="lg:col-span-2 bg-canvas border border-moss/40 rounded-xl p-4 overflow-x-auto relative min-h-[440px] flex items-center justify-center select-none">
            <svg
              viewBox="0 0 880 430"
              className="w-full max-w-[880px] h-auto drop-shadow-md"
              aria-label="Gatekeeper Service Topology Diagram"
            >
              <defs>
                <marker
                  id="arrow-emerald"
                  viewBox="0 0 10 10"
                  refX="6"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#31E99A" />
                </marker>
                <marker
                  id="arrow-titanium"
                  viewBox="0 0 10 10"
                  refX="6"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#899693" />
                </marker>
                <marker
                  id="arrow-amber"
                  viewBox="0 0 10 10"
                  refX="6"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#E9B35A" />
                </marker>
                <marker
                  id="arrow-red"
                  viewBox="0 0 10 10"
                  refX="6"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#F05460" />
                </marker>
                <marker
                  id="arrow-blue"
                  viewBox="0 0 10 10"
                  refX="6"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#38BDF8" />
                </marker>
              </defs>

              {/* Render Links */}
              {topology.links.map((link) => {
                const s = nodePositions[link.source]
                const t = nodePositions[link.target]
                if (!s || !t) return null

                const isTargetSelected = link.target === selectedNodeId || link.source === selectedNodeId
                let strokeColor = '#375B48'
                let markerId = 'arrow-titanium'

                if (link.circuitState === 'OPEN') {
                  strokeColor = '#F05460'
                  markerId = 'arrow-red'
                } else if (link.circuitState === 'HALF_OPEN') {
                  strokeColor = '#E9B35A'
                  markerId = 'arrow-amber'
                } else if (isTargetSelected) {
                  strokeColor = '#31E99A'
                  markerId = 'arrow-emerald'
                }

                // Bezier curve
                const dx = t.x - s.x
                const p1x = s.x + dx * 0.5
                const p1y = s.y
                const p2x = s.x + dx * 0.5
                const p2y = t.y
                const pathData = `M ${s.x} ${s.y} C ${p1x} ${p1y}, ${p2x} ${p2y}, ${t.x} ${t.y}`

                return (
                  <g key={`${link.source}-${link.target}`}>
                    <path
                      d={pathData}
                      fill="none"
                      stroke={strokeColor}
                      strokeWidth={isTargetSelected ? 2.5 : 1.5}
                      strokeDasharray={link.circuitState === 'OPEN' ? '4 4' : undefined}
                      markerEnd={`url(#${markerId})`}
                      className="transition-colors duration-300"
                    />
                    {/* Link Label */}
                    {link.label && (
                      <text
                        x={(s.x + t.x) / 2}
                        y={(s.y + t.y) / 2 - 8}
                        fill="#899693"
                        fontSize="10"
                        fontFamily="monospace"
                        textAnchor="middle"
                        className="pointer-events-none select-none"
                      >
                        {link.label}
                      </text>
                    )}
                  </g>
                )
              })}

              {/* Render Nodes */}
              {topology.nodes.map((node) => {
                const pos = nodePositions[node.id]
                if (!pos) return null

                const isSelected = node.id === selectedNodeId
                const statusColor = getStatusColor(node.status)
                const isGateway = node.id === 'gateway-core'

                return (
                  <g
                    key={node.id}
                    transform={`translate(${pos.x}, ${pos.y})`}
                    onClick={() => setSelectedNodeId(node.id)}
                    className="cursor-pointer group"
                    role="button"
                    tabIndex={0}
                    aria-label={`Node: ${node.label}`}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        setSelectedNodeId(node.id)
                      }
                    }}
                  >
                    {/* Focus / Selection Halo */}
                    {isSelected && (
                      <circle
                        r={isGateway ? 46 : 38}
                        fill="none"
                        stroke="#31E99A"
                        strokeWidth="2"
                        strokeDasharray="4 2"
                        className="animate-spin-slow opacity-80"
                      />
                    )}

                    {/* Node Background */}
                    <circle
                      r={isGateway ? 40 : 32}
                      fill="#131A18"
                      stroke={isSelected ? '#31E99A' : statusColor}
                      strokeWidth={isSelected ? 2 : 1.5}
                      className="transition-all duration-200 group-hover:scale-105"
                    />

                    {/* Node Core Icon placeholder or indicator */}
                    <circle
                      r={6}
                      cx={isGateway ? 24 : 18}
                      cy={isGateway ? -24 : -18}
                      fill={statusColor}
                      stroke="#070A09"
                      strokeWidth="1.5"
                    />

                    {/* Node Label Text */}
                    <text
                      y={isGateway ? 4 : 4}
                      fill="#DCE4E1"
                      fontSize={isGateway ? '12' : '11'}
                      fontWeight={isSelected ? 'bold' : 'normal'}
                      fontFamily="monospace"
                      textAnchor="middle"
                      className="pointer-events-none select-none"
                    >
                      {node.label}
                    </text>

                    {/* Node Subtitle / Port */}
                    <text
                      y={isGateway ? 58 : 50}
                      fill="#899693"
                      fontSize="9"
                      fontFamily="monospace"
                      textAnchor="middle"
                      className="pointer-events-none select-none"
                    >
                      {node.endpointOrHost}
                    </text>

                    {/* Circuit Breaker Overlay Pill */}
                    {node.circuitBreakerState && (
                      <g transform={`translate(0, ${isGateway ? -48 : -40})`}>
                        <rect
                          x="-35"
                          y="-10"
                          width="70"
                          height="18"
                          rx="9"
                          fill="#070A09"
                          stroke={
                            node.circuitBreakerState === 'OPEN'
                              ? '#F05460'
                              : node.circuitBreakerState === 'HALF_OPEN'
                              ? '#E9B35A'
                              : '#31E99A'
                          }
                          strokeWidth="1"
                        />
                        <text
                          y="3"
                          fill={
                            node.circuitBreakerState === 'OPEN'
                              ? '#F05460'
                              : node.circuitBreakerState === 'HALF_OPEN'
                              ? '#E9B35A'
                              : '#31E99A'
                          }
                          fontSize="9"
                          fontWeight="bold"
                          fontFamily="monospace"
                          textAnchor="middle"
                        >
                          CB: {node.circuitBreakerState}
                        </text>
                      </g>
                    )}
                  </g>
                )
              })}
            </svg>
          </div>

          {/* Node Inspector Drawer */}
          <div className="bg-surface border border-moss/40 rounded-xl p-5 flex flex-col justify-between">
            <div className="space-y-4">
              <div className="flex items-start justify-between border-b border-moss/30 pb-3">
                <div>
                  <div className="flex items-center gap-2">
                    {React.createElement(getNodeIcon(selectedNode.type), {
                      className: 'w-4 h-4 text-emerald',
                    })}
                    <h3 className="font-mono font-bold text-silver-frost text-sm">
                      {selectedNode.label}
                    </h3>
                  </div>
                  <p className="text-[11px] text-titanium font-mono mt-0.5">
                    ID: {selectedNode.serviceId}
                  </p>
                </div>
                {getStatusBadge(selectedNode.status)}
              </div>

              {/* Status Rationale */}
              <div className="bg-canvas/60 p-3 rounded-lg border border-moss/30 space-y-1">
                <span className="text-[10px] uppercase font-mono tracking-wider text-titanium flex items-center gap-1">
                  <Info className="w-3 h-3 text-emerald" /> Operational Rationale
                </span>
                <p className="text-xs font-mono text-silver-frost leading-relaxed">
                  {selectedNode.statusReason || 'Operational telemetry nominal.'}
                </p>
              </div>

              {/* Metadata Details */}
              <div className="space-y-2 text-xs font-mono">
                <div className="flex justify-between py-1 border-b border-moss/20">
                  <span className="text-titanium">Role</span>
                  <span className="text-silver-frost text-right max-w-[180px] truncate">
                    {selectedNode.role}
                  </span>
                </div>
                <div className="flex justify-between py-1 border-b border-moss/20">
                  <span className="text-titanium">Target Endpoint</span>
                  <span className="text-emerald font-semibold">{selectedNode.endpointOrHost}</span>
                </div>
                <div className="flex justify-between py-1 border-b border-moss/20">
                  <span className="text-titanium">Dependency Type</span>
                  <span className="text-silver-frost">{selectedNode.type}</span>
                </div>
                {selectedNode.circuitBreakerState && (
                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Circuit Breaker</span>
                    <span
                      className={`font-bold ${
                        selectedNode.circuitBreakerState === 'OPEN'
                          ? 'text-signal-red'
                          : selectedNode.circuitBreakerState === 'HALF_OPEN'
                          ? 'text-warning'
                          : 'text-emerald'
                      }`}
                    >
                      {selectedNode.circuitBreakerState}
                    </span>
                  </div>
                )}
                {selectedNode.failureRate !== undefined && (
                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Observed Failure Rate</span>
                    <span className="text-silver-frost">
                      {selectedNode.failureRate.toFixed(1)}%
                    </span>
                  </div>
                )}
                {selectedNode.inFlightCalls !== undefined && selectedNode.maxConcurrency !== undefined && (
                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Bulkhead Permits</span>
                    <span className="text-silver-frost">
                      {selectedNode.inFlightCalls} in-flight / {selectedNode.maxConcurrency} max
                    </span>
                  </div>
                )}
                <div className="flex justify-between py-1">
                  <span className="text-titanium">Last Observed</span>
                  <span className="text-titanium text-[10px]">
                    {selectedNode.lastObserved ? new Date(selectedNode.lastObserved).toLocaleTimeString() : 'Recently'}
                  </span>
                </div>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="space-y-2 pt-4 border-t border-moss/30">
              {selectedNode.serviceId.includes('orders') && onSelectRoute && (
                <button
                  type="button"
                  onClick={() => onSelectRoute('orders-service-v1')}
                  className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-emerald/10 hover:bg-emerald/20 text-emerald border border-emerald/40 rounded-lg text-xs font-mono font-medium transition-colors"
                >
                  <span>Inspect Route Breaker & Bulkhead</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </button>
              )}
              {selectedNode.serviceId.includes('inventory') && onSelectRoute && (
                <button
                  type="button"
                  onClick={() => onSelectRoute('inventory-service-v1')}
                  className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-emerald/10 hover:bg-emerald/20 text-emerald border border-emerald/40 rounded-lg text-xs font-mono font-medium transition-colors"
                >
                  <span>Inspect Route Breaker & Bulkhead</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </button>
              )}
              {onNavigateAnalytics && (
                <button
                  type="button"
                  onClick={() => onNavigateAnalytics(selectedNode.endpointOrHost)}
                  className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-canvas hover:bg-surface-elevated text-titanium hover:text-silver-frost border border-moss/30 rounded-lg text-xs font-mono transition-colors"
                >
                  <span>Query Route Usage Analytics</span>
                  <ExternalLink className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>
        </div>
      ) : (
        /* Alternative Responsive List / Directory View */
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {topology.nodes.map((node) => {
            const Icon = getNodeIcon(node.type)
            return (
              <div
                key={node.id}
                onClick={() => setSelectedNodeId(node.id)}
                className={`p-4 rounded-xl border transition-all cursor-pointer ${
                  selectedNodeId === node.id
                    ? 'bg-surface border-emerald shadow-emerald-sm'
                    : 'bg-canvas border-moss/30 hover:border-moss/60'
                }`}
              >
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-2.5">
                    <div className="p-2 rounded-lg bg-moss/20 border border-moss/40 text-emerald">
                      <Icon className="w-4 h-4" />
                    </div>
                    <div>
                      <h4 className="font-mono font-bold text-silver-frost text-sm">
                        {node.label}
                      </h4>
                      <p className="text-[10px] text-titanium font-mono">{node.endpointOrHost}</p>
                    </div>
                  </div>
                  {getStatusBadge(node.status)}
                </div>

                <p className="text-xs font-mono text-titanium mt-3 line-clamp-2">
                  {node.statusReason || node.role}
                </p>

                {node.circuitBreakerState && (
                  <div className="mt-3 pt-2 border-t border-moss/20 flex items-center justify-between text-xs font-mono">
                    <span className="text-titanium">Circuit State:</span>
                    <span
                      className={`font-bold ${
                        node.circuitBreakerState === 'OPEN'
                          ? 'text-signal-red'
                          : node.circuitBreakerState === 'HALF_OPEN'
                          ? 'text-warning'
                          : 'text-emerald'
                      }`}
                    >
                      {node.circuitBreakerState}
                    </span>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
