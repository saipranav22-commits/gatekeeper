import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { analyticsService } from '@/services/analyticsService'
import { consumerService } from '@/services/consumerService'
import { useGatewayHealth } from '@/context/GatewayHealthContext'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { DateRangePicker, DateRange } from '@/components/ui/DateRangePicker'
import { Skeleton } from '@/components/ui/Skeleton'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { formatNumber, formatPercentage, formatDate } from '@/lib/utils'
import {
  Activity,
  CheckCircle2,
  AlertOctagon,
  ShieldAlert,
  Percent,
  ArrowUpRight,
  RefreshCw,
  TrendingUp,
  Server,
  Zap,
  Sliders,
  ShieldCheck,
  AlertTriangle,
  Radio,
  Layers,
  Clock,
} from 'lucide-react'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  BarChart,
  Bar,
  Cell,
} from 'recharts'

export const Overview: React.FC = () => {
  const {
    status: gatewayHealthStatus,
    resilienceData,
    lastChecked: healthLastChecked,
    isChecking: healthChecking,
    pollingInterval,
    setPollingInterval,
    refetch: refetchHealth,
  } = useGatewayHealth()

  const [dateRange, setDateRange] = useState<DateRange>(() => {
    const end = new Date()
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000)
    return {
      start: start.toISOString(),
      end: end.toISOString(),
      preset: '24h',
    }
  })

  const [granularity, setGranularity] = useState<'hourly' | 'daily'>('hourly')

  const handleDateRangeChange = (newRange: DateRange) => {
    setDateRange(newRange)
    if (newRange.preset === '30d') {
      setGranularity('daily')
    } else {
      setGranularity('hourly')
    }
  }

  // 1. Overview Query
  const {
    data: overview,
    isLoading: overviewLoading,
    error: overviewError,
    refetch: refetchOverview,
    isFetching: overviewFetching,
  } = useQuery({
    queryKey: ['analytics', 'overview', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getOverview(dateRange.start, dateRange.end),
    refetchInterval: pollingInterval > 0 ? pollingInterval : false,
  })

  // 2. TimeSeries Query
  const {
    data: timeSeries,
    isLoading: timeSeriesLoading,
    error: timeSeriesError,
    refetch: refetchTimeSeries,
    isFetching: timeSeriesFetching,
  } = useQuery({
    queryKey: ['analytics', 'timeseries', dateRange.start, dateRange.end, granularity],
    queryFn: () => analyticsService.getTimeSeries(dateRange.start, dateRange.end, granularity),
    refetchInterval: pollingInterval > 0 ? pollingInterval : false,
  })

  // 3. Top Consumers Query
  const {
    data: topConsumers,
    isLoading: topConsumersLoading,
    error: topConsumersError,
    refetch: refetchTopConsumers,
    isFetching: topConsumersFetching,
  } = useQuery({
    queryKey: ['analytics', 'topConsumers', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getTopConsumers(dateRange.start, dateRange.end, 5),
    refetchInterval: pollingInterval > 0 ? pollingInterval : false,
  })

  // 4. Consumers Summary Count Query
  const { data: consumerPage } = useQuery({
    queryKey: ['consumers', 'count'],
    queryFn: () => consumerService.listConsumers(0, 1),
    staleTime: 60000,
  })

  const isRefreshing =
    overviewFetching || timeSeriesFetching || topConsumersFetching || healthChecking

  const handleRefreshAll = () => {
    refetchHealth()
    refetchOverview()
    refetchTimeSeries()
    refetchTopConsumers()
  }

  // Transform usage by plan for horizontal bar chart
  const planData = React.useMemo(() => {
    if (!overview?.usageByPlan) return []
    return Object.entries(overview.usageByPlan).map(([name, count]) => ({
      name,
      requests: count,
    }))
  }, [overview])

  const planColors = ['#31E99A', '#B8F3D6', '#E9B35A', '#899693', '#F05460']

  // Derive Operational Insights from verified metrics
  const operationalInsights = React.useMemo(() => {
    const insights: Array<{
      id: string
      type: 'healthy' | 'warning' | 'critical'
      title: string
      description: string
    }> = []

    if (!overview) return insights

    // Check circuit breaker status
    const trippedRoutes = (resilienceData || []).filter(
      (r) => r.circuitBreakerState === 'OPEN' || r.circuitBreakerState === 'HALF_OPEN'
    )
    if (trippedRoutes.length > 0) {
      insights.push({
        id: 'circuit-tripped',
        type: 'critical',
        title: `Circuit Breaker Tripped on ${trippedRoutes.map((r) => r.routeId).join(', ')}`,
        description:
          'Upstream service degradation exceeded safety thresholds. Concurrency bulkhead is shedding traffic to prevent cascading failure.',
      })
    }

    // Check rate limit denial percentage
    if (overview.rateLimitDenialPercentage > 20) {
      insights.push({
        id: 'elevated-throttles',
        type: 'warning',
        title: `High Rate-Limiting Volume (${formatPercentage(overview.rateLimitDenialPercentage)})`,
        description:
          'More than 20% of traffic is hitting token bucket exhaustion. Consider verifying consumer subscription plan allocations.',
      })
    }

    // Check 5xx error requests
    if (overview.errorRequests > 0) {
      insights.push({
        id: 'upstream-5xx',
        type: 'warning',
        title: `${formatNumber(overview.errorRequests)} Server Errors (5xx)`,
        description:
          'Upstream microservices or routing proxy returned server-side fault codes during the selected time window.',
      })
    }

    // If completely healthy
    if (insights.length === 0 && overview.totalRequests > 0) {
      insights.push({
        id: 'nominal',
        type: 'healthy',
        title: 'All Gateway Subsystems Operating Nominally',
        description:
          'Token buckets, authentication checks, upstream routing, and circuit breakers are operating within configured error and latency limits.',
      })
    }

    return insights
  }, [overview, resilienceData])

  // Calculate Success Rate %
  const successRate =
    overview && overview.totalRequests > 0
      ? ((overview.allowedRequests / overview.totalRequests) * 100).toFixed(1)
      : '100.0'

  return (
    <div className="space-y-6">
      {/* 1. Command Center Control Bar */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <div className="flex items-center gap-2.5">
            <h1 className="text-2xl font-bold tracking-tight text-silver-frost">Command Center</h1>
            <Badge
              variant={
                gatewayHealthStatus === 'HEALTHY'
                  ? 'success'
                  : gatewayHealthStatus === 'DEGRADED'
                  ? 'warning'
                  : gatewayHealthStatus === 'OFFLINE'
                  ? 'danger'
                  : 'default'
              }
            >
              {gatewayHealthStatus}
            </Badge>
          </div>
          <p className="text-xs text-titanium mt-1 font-mono">
            Mission-critical gateway ingress, token bucket quota telemetry, and upstream resilience
            {consumerPage?.totalElements !== undefined && (
              <span className="text-emerald ml-2">• {consumerPage.totalElements} Registered Tenants</span>
            )}
          </p>
        </div>

        {/* Controls: Date range + Polling Interval + Refresh */}
        <div className="flex flex-wrap items-center gap-2 sm:gap-3">
          <DateRangePicker range={dateRange} onChange={handleDateRangeChange} />

          {/* Polling Interval Selector */}
          <div className="flex items-center gap-1 bg-surface border border-moss-subtle rounded-lg p-1 text-xs text-titanium">
            <Clock className="w-3.5 h-3.5 ml-1 text-titanium-muted" />
            <select
              value={pollingInterval}
              onChange={(e) => setPollingInterval(Number(e.target.value))}
              className="bg-transparent text-silver-frost text-xs py-0.5 px-1 focus:outline-none cursor-pointer"
              title="Auto-refresh rate"
              aria-label="Auto-refresh rate"
            >
              <option value="0" className="bg-surface text-silver-frost">
                Paused
              </option>
              <option value="15000" className="bg-surface text-silver-frost">
                15s refresh
              </option>
              <option value="30000" className="bg-surface text-silver-frost">
                30s refresh
              </option>
              <option value="60000" className="bg-surface text-silver-frost">
                60s refresh
              </option>
            </select>
          </div>

          {healthLastChecked && (
            <span className="text-[11px] text-titanium hidden xl:inline font-mono">
              Synced {healthLastChecked.toLocaleTimeString()}
            </span>
          )}

          <Button
            variant="outline"
            size="sm"
            onClick={handleRefreshAll}
            disabled={isRefreshing}
            className="cursor-pointer"
            title="Refresh command center metrics"
            aria-label="Refresh command center metrics"
          >
            <RefreshCw className={`w-3.5 h-3.5 mr-1.5 ${isRefreshing ? 'animate-spin text-emerald' : ''}`} />
            <span>Sync</span>
          </Button>
        </div>
      </div>

      {/* Disconnected / Error Warning Banner */}
      {overviewError && (
        <div
          className="p-4 rounded-xl bg-signal-red/10 border border-signal-red/30 text-silver-frost flex items-start gap-3"
          role="alert"
        >
          <AlertOctagon className="w-5 h-5 text-signal-red shrink-0 mt-0.5" />
          <div className="text-xs space-y-1">
            <div className="font-semibold text-signal-red">Failed to Fetch Overview Telemetry</div>
            <p className="text-titanium leading-relaxed">
              An error occurred while querying <code className="text-silver-frost font-mono">/api/v1/analytics/overview</code>. Verify operator authorization and gateway database connectivity.
            </p>
          </div>
        </div>
      )}

      {gatewayHealthStatus === 'OFFLINE' && !overviewError && (
        <div
          className="p-4 rounded-xl bg-signal-red/10 border border-signal-red/30 text-silver-frost flex items-start gap-3"
          role="alert"
        >
          <AlertOctagon className="w-5 h-5 text-signal-red shrink-0 mt-0.5" />
          <div className="text-xs space-y-1">
            <div className="font-semibold text-signal-red">Gateway Backend Offline or Unreachable</div>
            <p className="text-titanium leading-relaxed">
              Unable to reach <code className="text-silver-frost font-mono">/api/v1/health</code>. Live metrics and telemetry
              may reflect stale cache or default values until gateway connectivity is restored.
            </p>
          </div>
        </div>
      )}

      {/* 2. Resilience & Upstream Services Status Bar */}
      {resilienceData && resilienceData.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {resilienceData.map((route) => {
            const isTripped =
              route.circuitBreakerState === 'OPEN' || route.circuitBreakerState === 'HALF_OPEN'
            return (
              <div
                key={route.routeId}
                className="p-3.5 bg-surface rounded-xl border border-moss-subtle flex items-center justify-between text-xs shadow-card-dark"
              >
                <div className="flex items-center gap-3">
                  <div
                    className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                      isTripped
                        ? 'bg-signal-red/10 text-signal-red border border-signal-red/30'
                        : 'bg-emerald/10 text-emerald border border-emerald/30'
                    }`}
                  >
                    <Server className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="font-semibold text-silver-frost font-mono">{route.routeId}</div>
                    <div className="text-[11px] text-titanium">
                      Bulkhead in-flight: <span className="font-mono text-silver-frost">{route.bulkheadInFlightCalls}</span> /{' '}
                      {route.bulkheadMaxConcurrent}
                    </div>
                  </div>
                </div>
                <div className="text-right">
                  <Badge variant={route.circuitBreakerState === 'CLOSED' ? 'success' : 'danger'}>
                    {route.circuitBreakerState}
                  </Badge>
                  <div className="text-[10px] text-titanium mt-1 font-mono">
                    Fail: {formatPercentage(route.failureRate)}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {/* 3. Primary KPI Stat Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-6 gap-4">
        {/* Total Ingress */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Total Traffic</span>
              <div className="p-1.5 bg-surface-elevated text-emerald rounded-md border border-moss-subtle">
                <Activity className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-silver-frost tabular-nums">
                  {formatNumber(overview?.totalRequests)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">Gateway ingress</span>
            </div>
          </CardContent>
        </Card>

        {/* Allowed (200 OK) */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Allowed (200)</span>
              <div className="p-1.5 bg-emerald/10 text-emerald rounded-md border border-emerald/30">
                <CheckCircle2 className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-emerald tabular-nums">
                  {formatNumber(overview?.allowedRequests)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">{successRate}% success rate</span>
            </div>
          </CardContent>
        </Card>

        {/* Rate Limited (429) */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Throttled (429)</span>
              <div className="p-1.5 bg-warning-amber/10 text-warning-amber rounded-md border border-warning-amber/30">
                <AlertOctagon className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-warning-amber tabular-nums">
                  {formatNumber(overview?.rateLimitedRequests)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">Token bucket empty</span>
            </div>
          </CardContent>
        </Card>

        {/* Scope Denied (403) */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Denied (403)</span>
              <div className="p-1.5 bg-signal-red/10 text-signal-red rounded-md border border-signal-red/30">
                <ShieldAlert className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-signal-red tabular-nums">
                  {formatNumber(overview?.scopeDeniedRequests)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">Scope violation</span>
            </div>
          </CardContent>
        </Card>

        {/* Server Errors (5xx) */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Errors (5xx)</span>
              <div className="p-1.5 bg-surface-elevated text-titanium rounded-md border border-moss-subtle">
                <Zap className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-silver-frost tabular-nums">
                  {formatNumber(overview?.errorRequests)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">Fault responses</span>
            </div>
          </CardContent>
        </Card>

        {/* Quota Denial % */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Throttle Rate</span>
              <div className="p-1.5 bg-surface-elevated text-titanium rounded-md border border-moss-subtle">
                <Percent className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                <div className="text-xl font-bold text-silver-frost tabular-nums">
                  {formatPercentage(overview?.rateLimitDenialPercentage)}
                </div>
              )}
              <span className="text-[10px] text-titanium font-mono">Of total volume</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 4. Operational Insights Bar (Rule-based) */}
      {operationalInsights.length > 0 && (
        <div className="space-y-2">
          {operationalInsights.map((insight) => (
            <div
              key={insight.id}
              className={`p-3.5 rounded-xl border flex items-start gap-3 text-xs ${
                insight.type === 'critical'
                  ? 'bg-signal-red/10 border-signal-red/30 text-silver-frost'
                  : insight.type === 'warning'
                  ? 'bg-warning-amber/10 border-warning-amber/30 text-silver-frost'
                  : 'bg-surface border-moss-subtle text-silver-frost'
              }`}
            >
              {insight.type === 'critical' ? (
                <AlertTriangle className="w-4 h-4 text-signal-red shrink-0 mt-0.5" />
              ) : insight.type === 'warning' ? (
                <AlertOctagon className="w-4 h-4 text-warning-amber shrink-0 mt-0.5" />
              ) : (
                <ShieldCheck className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
              )}
              <div className="flex-1">
                <span className="font-semibold">{insight.title}: </span>
                <span className="text-titanium">{insight.description}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 5. Main Charts Row: Time Series (2 cols) + Plan Usage (1 col) */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Interactive Throughput Timeline */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <TrendingUp className="w-4 h-4 text-emerald" />
                  Traffic Throughput Timeline
                </CardTitle>
                <CardDescription>
                  Temporal request distribution (Allowed vs Rate-Limited vs Scope Denied)
                </CardDescription>
              </div>

              {/* Granularity Switcher */}
              <div className="inline-flex rounded-lg border border-moss-subtle bg-surface-elevated p-0.5">
                <button
                  type="button"
                  onClick={() => setGranularity('hourly')}
                  className={`px-2.5 py-1 text-xs font-medium rounded-md transition-all cursor-pointer ${
                    granularity === 'hourly'
                      ? 'bg-moss/40 text-emerald font-semibold border border-emerald/30'
                      : 'text-titanium hover:text-silver-frost'
                  }`}
                >
                  Hourly
                </button>
                <button
                  type="button"
                  onClick={() => setGranularity('daily')}
                  className={`px-2.5 py-1 text-xs font-medium rounded-md transition-all cursor-pointer ${
                    granularity === 'daily'
                      ? 'bg-moss/40 text-emerald font-semibold border border-emerald/30'
                      : 'text-titanium hover:text-silver-frost'
                  }`}
                >
                  Daily
                </button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {timeSeriesLoading ? (
              <div className="h-72 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : timeSeriesError ? (
              <div className="h-72 flex flex-col items-center justify-center text-titanium text-center p-4">
                <AlertOctagon className="w-8 h-8 text-signal-red mb-2" />
                <p className="text-sm font-medium text-silver-frost">Failed to retrieve time-series metrics</p>
                <span className="text-xs text-titanium mt-1 font-mono">Backend returned error response</span>
              </div>
            ) : !timeSeries || timeSeries.length === 0 ? (
              <div className="h-72 flex flex-col items-center justify-center text-titanium text-center p-4">
                <Radio className="w-8 h-8 text-titanium-muted mb-2 animate-pulse" />
                <p className="text-sm font-medium text-silver-frost">No gateway traffic logged in this time window</p>
                <span className="text-xs text-titanium mt-1">
                  Submit HTTP requests through gateway proxy routes to generate real-time metrics
                </span>
              </div>
            ) : (
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={timeSeries} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <defs>
                      <linearGradient id="emeraldGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#31E99A" stopOpacity={0.35} />
                        <stop offset="95%" stopColor="#31E99A" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="amberGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#E9B35A" stopOpacity={0.35} />
                        <stop offset="95%" stopColor="#E9B35A" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="redGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#F05460" stopOpacity={0.35} />
                        <stop offset="95%" stopColor="#F05460" stopOpacity={0.0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1E3429" />
                    <XAxis
                      dataKey="bucket"
                      tickFormatter={(val) => formatDate(val).split(',')[0]}
                      tick={{ fontSize: 11, fill: '#899693' }}
                      axisLine={{ stroke: '#22382D' }}
                    />
                    <YAxis
                      tick={{ fontSize: 11, fill: '#899693' }}
                      axisLine={{ stroke: '#22382D' }}
                      tickFormatter={(val) => formatNumber(val)}
                    />
                    <Tooltip
                      formatter={(value: unknown, name: unknown) => [
                        formatNumber(typeof value === 'number' ? value : 0),
                        name === 'allowedRequests'
                          ? 'Allowed (200)'
                          : name === 'rateLimitedRequests'
                          ? 'Rate Limited (429)'
                          : name === 'scopeDeniedRequests'
                          ? 'Scope Denied (403)'
                          : String(name || ''),
                      ]}
                      labelFormatter={(label) => formatDate(String(label))}
                      contentStyle={{
                        backgroundColor: '#131A18',
                        color: '#DCE4E1',
                        borderRadius: '0.5rem',
                        border: '1px solid #375B48',
                        fontSize: '12px',
                        boxShadow: '0 4px 20px -2px rgba(0, 0, 0, 0.6)',
                      }}
                    />
                    <Area
                      type="monotone"
                      dataKey="allowedRequests"
                      name="Allowed (200)"
                      stroke="#31E99A"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#emeraldGrad)"
                    />
                    <Area
                      type="monotone"
                      dataKey="rateLimitedRequests"
                      name="Rate Limited (429)"
                      stroke="#E9B35A"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#amberGrad)"
                    />
                    <Area
                      type="monotone"
                      dataKey="scopeDeniedRequests"
                      name="Scope Denied (403)"
                      stroke="#F05460"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#redGrad)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Traffic by Subscription Plan */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Layers className="w-4 h-4 text-emerald" />
              Volume by Subscription Tier
            </CardTitle>
            <CardDescription>Gateway request distribution grouped by active rate plan</CardDescription>
          </CardHeader>
          <CardContent>
            {overviewLoading ? (
              <div className="h-72 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : planData.length === 0 ? (
              <div className="h-72 flex flex-col items-center justify-center text-titanium text-center p-4">
                <p className="text-sm font-medium text-silver-frost">No plan data recorded</p>
                <span className="text-xs text-titanium mt-1">
                  Requests will be aggregated automatically by plan key snapshot
                </span>
              </div>
            ) : (
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={planData} layout="vertical" margin={{ top: 10, right: 20, left: 10, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#1E3429" />
                    <XAxis
                      type="number"
                      tick={{ fontSize: 11, fill: '#899693' }}
                      axisLine={{ stroke: '#22382D' }}
                      tickFormatter={(val) => formatNumber(val)}
                    />
                    <YAxis
                      dataKey="name"
                      type="category"
                      tick={{ fontSize: 11, fill: '#DCE4E1', fontWeight: 600 }}
                      axisLine={{ stroke: '#22382D' }}
                    />
                    <Tooltip
                      formatter={(value: unknown) => [
                        formatNumber(typeof value === 'number' ? value : 0),
                        'Requests',
                      ]}
                      contentStyle={{
                        backgroundColor: '#131A18',
                        color: '#DCE4E1',
                        borderRadius: '0.5rem',
                        border: '1px solid #375B48',
                        fontSize: '12px',
                      }}
                    />
                    <Bar dataKey="requests" radius={[0, 4, 4, 0]}>
                      {planData.map((_, index) => (
                        <Cell key={`cell-${index}`} fill={planColors[index % planColors.length]} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 6. Top Consumers Ranked Table */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-base flex items-center gap-2">
                <Sliders className="w-4 h-4 text-emerald" />
                Top Consumer Organizations
              </CardTitle>
              <CardDescription>
                Active tenants ranked by gateway invocation volume in selected window
              </CardDescription>
            </div>
            <Link
              to="/consumers"
              className="text-xs font-semibold text-emerald hover:text-emerald-hover flex items-center gap-1 transition-colors"
            >
              <span>View All Consumers</span>
              <ArrowUpRight className="w-3.5 h-3.5" />
            </Link>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {topConsumersLoading ? (
            <div className="p-6 space-y-3">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : topConsumersError ? (
            <div className="p-8 text-center text-titanium">
              <p className="text-sm font-medium text-signal-red">Unable to retrieve consumer rankings</p>
              <span className="text-xs mt-1 font-mono">Verify database connection and JWT authentication</span>
            </div>
          ) : !topConsumers || topConsumers.length === 0 ? (
            <div className="p-8 text-center text-titanium">
              <p className="text-sm font-medium text-silver-frost">No consumer traffic recorded in this period</p>
              <span className="text-xs mt-1 text-titanium-muted">
                Consumers will populate automatically once keys are active
              </span>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-silver-frost tabular-nums">
                <thead className="bg-surface-elevated/80 border-b border-moss-subtle text-[11px] font-semibold text-titanium uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3">Consumer Organization</th>
                    <th className="px-6 py-3">Subscription Tier</th>
                    <th className="px-6 py-3">Total Ingress</th>
                    <th className="px-6 py-3">Allowed (200)</th>
                    <th className="px-6 py-3">Throttled (429)</th>
                    <th className="px-6 py-3">Denial Rate</th>
                    <th className="px-6 py-3 text-right">Inspection</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-moss-subtle/40 bg-surface">
                  {topConsumers.map((c) => (
                    <tr key={c.consumerId} className="hover:bg-surface-elevated/50 transition-colors">
                      <td className="px-6 py-3.5 font-medium">
                        <Link
                          to={`/consumers/${c.consumerId}`}
                          className="hover:text-emerald transition-colors font-semibold text-silver-frost"
                        >
                          {c.consumerName}
                        </Link>
                        <div className="text-xs text-titanium font-mono">{c.consumerEmail}</div>
                      </td>
                      <td className="px-6 py-3.5">
                        <Badge variant="purple">{c.plan}</Badge>
                      </td>
                      <td className="px-6 py-3.5 font-semibold text-silver-frost">{formatNumber(c.totalRequests)}</td>
                      <td className="px-6 py-3.5 text-emerald font-medium">{formatNumber(c.allowedRequests)}</td>
                      <td className="px-6 py-3.5 text-warning-amber font-medium">{formatNumber(c.rateLimitedRequests)}</td>
                      <td className="px-6 py-3.5">
                        <span
                          className={`font-semibold ${
                            c.rateLimitDenialPercentage > 20
                              ? 'text-signal-red'
                              : c.rateLimitDenialPercentage > 0
                              ? 'text-warning-amber'
                              : 'text-titanium'
                          }`}
                        >
                          {formatPercentage(c.rateLimitDenialPercentage)}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 text-right">
                        <Link
                          to={`/consumers/${c.consumerId}`}
                          className="text-xs font-medium text-emerald hover:text-emerald-hover flex items-center justify-end gap-1"
                        >
                          <span>Drilldown</span>
                          <ArrowUpRight className="w-3 h-3" />
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
