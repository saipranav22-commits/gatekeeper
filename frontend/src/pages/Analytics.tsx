import React, { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { analyticsService } from '@/services/analyticsService'
import { consumerService } from '@/services/consumerService'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { DateRangePicker, DateRange } from '@/components/ui/DateRangePicker'
import { Skeleton } from '@/components/ui/Skeleton'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { formatNumber, formatPercentage, formatDate } from '@/lib/utils'
import { ConsumerDto } from '@/types/consumer'
import {
  TrendingUp,
  Activity,
  RefreshCw,
  PieChart as PieIcon,
  Filter,
  CheckCircle2,
  AlertOctagon,
  ShieldAlert,
  ArrowUpDown,
  ExternalLink,
  Layers,
  ChevronLeft,
  ChevronRight,
  Database,
} from 'lucide-react'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
  PieChart,
  Pie,
  Cell,
  BarChart,
  Bar,
} from 'recharts'

export const Analytics: React.FC = () => {
  // Global Filters
  const [dateRange, setDateRange] = useState<DateRange>(() => {
    const end = new Date()
    const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
    return {
      start: start.toISOString(),
      end: end.toISOString(),
      preset: '7d',
    }
  })

  const [granularity, setGranularity] = useState<'hourly' | 'daily'>('hourly')
  const [selectedConsumerId, setSelectedConsumerId] = useState<string>('ALL')
  const [outcomeFilter, setOutcomeFilter] = useState<string>('ALL')
  const [eventPage, setEventPage] = useState<number>(0)
  const [sortField, setSortField] = useState<'total' | 'allowed' | 'limited' | 'rate'>('total')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')

  const handleDateRangeChange = (newRange: DateRange) => {
    setDateRange(newRange)
    if (newRange.preset === '30d') {
      setGranularity('daily')
    } else {
      setGranularity('hourly')
    }
    setEventPage(0)
  }

  // 1. Fetch Consumers List for Dropdown Filter
  const { data: consumersPage } = useQuery({
    queryKey: ['consumers', 'dropdown'],
    queryFn: () => consumerService.listConsumers(0, 50),
    staleTime: 60000,
  })

  const consumers = consumersPage?.content || []

  // 2. Global Overview Query
  const {
    data: overview,
    isLoading: overviewLoading,
    refetch: refetchOverview,
    isFetching: overviewFetching,
  } = useQuery({
    queryKey: ['analytics', 'overview', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getOverview(dateRange.start, dateRange.end),
    enabled: selectedConsumerId === 'ALL',
  })

  // 3. TimeSeries Query
  const {
    data: timeSeries,
    isLoading: timeSeriesLoading,
    refetch: refetchTimeSeries,
    isFetching: timeSeriesFetching,
  } = useQuery({
    queryKey: ['analytics', 'timeseries', dateRange.start, dateRange.end, granularity],
    queryFn: () => analyticsService.getTimeSeries(dateRange.start, dateRange.end, granularity),
  })

  // 4. Top Consumers Query
  const {
    data: topConsumers,
    isLoading: topLoading,
    refetch: refetchTop,
    isFetching: topFetching,
  } = useQuery({
    queryKey: ['analytics', 'topConsumers', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getTopConsumers(dateRange.start, dateRange.end, 15),
  })

  // 5. Specific Consumer Analytics (When filtered)
  const {
    data: consumerAnalytics,
    isLoading: consumerAnalyticsLoading,
    refetch: refetchConsumerAnalytics,
  } = useQuery({
    queryKey: ['analytics', 'consumer', selectedConsumerId, dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getConsumerAnalytics(selectedConsumerId, dateRange.start, dateRange.end),
    enabled: selectedConsumerId !== 'ALL',
  })

  // 6. Specific Consumer Usage Events (Paginated audit logs)
  const {
    data: consumerUsagePage,
    isLoading: consumerUsageLoading,
    refetch: refetchConsumerUsage,
  } = useQuery({
    queryKey: ['analytics', 'consumerUsage', selectedConsumerId, dateRange.start, dateRange.end, eventPage],
    queryFn: () =>
      analyticsService.getConsumerUsageEvents(selectedConsumerId, dateRange.start, dateRange.end, eventPage, 10),
    enabled: selectedConsumerId !== 'ALL',
  })

  const isRefreshing = overviewFetching || timeSeriesFetching || topFetching

  const handleRefresh = () => {
    if (selectedConsumerId === 'ALL') {
      refetchOverview()
      refetchTimeSeries()
      refetchTop()
    } else {
      refetchConsumerAnalytics()
      refetchConsumerUsage()
      refetchTimeSeries()
    }
  }

  // Active metrics: either global overview or targeted consumer analytics
  const activeMetrics = useMemo(() => {
    if (selectedConsumerId !== 'ALL' && consumerAnalytics) {
      const total = consumerAnalytics.totalRequests
      const limited = consumerAnalytics.rateLimitedRequests
      const denialRate = total > 0 ? (limited * 100.0) / total : 0
      return {
        totalRequests: consumerAnalytics.totalRequests,
        allowedRequests: consumerAnalytics.allowedRequests,
        rateLimitedRequests: consumerAnalytics.rateLimitedRequests,
        scopeDeniedRequests: consumerAnalytics.scopeDeniedRequests,
        errorRequests: 0,
        rateLimitDenialPercentage: Math.round(denialRate * 100) / 100,
        label: consumerAnalytics.consumerName,
      }
    }
    return {
      totalRequests: overview?.totalRequests || 0,
      allowedRequests: overview?.allowedRequests || 0,
      rateLimitedRequests: overview?.rateLimitedRequests || 0,
      scopeDeniedRequests: overview?.scopeDeniedRequests || 0,
      errorRequests: overview?.errorRequests || 0,
      rateLimitDenialPercentage: overview?.rateLimitDenialPercentage || 0,
      label: 'Global Control Plane',
    }
  }, [selectedConsumerId, consumerAnalytics, overview])

  // Filtered TimeSeries data based on Outcome filter
  const filteredTimeSeries = useMemo(() => {
    if (!timeSeries) return []
    if (outcomeFilter === 'ALL') return timeSeries
    return timeSeries.map((pt) => ({
      ...pt,
      allowedRequests: outcomeFilter === 'ALLOWED' ? pt.allowedRequests : 0,
      rateLimitedRequests: outcomeFilter === 'RATE_LIMITED' ? pt.rateLimitedRequests : 0,
      scopeDeniedRequests: outcomeFilter === 'SCOPE_DENIED' ? pt.scopeDeniedRequests : 0,
    }))
  }, [timeSeries, outcomeFilter])

  // Outcome breakdown data for Pie/Donut Chart
  const outcomeData = useMemo(() => {
    if (!activeMetrics) return []
    return [
      { name: 'Allowed (200)', value: activeMetrics.allowedRequests, color: '#31E99A' },
      { name: 'Rate Limited (429)', value: activeMetrics.rateLimitedRequests, color: '#E9B35A' },
      { name: 'Scope Denied (403)', value: activeMetrics.scopeDeniedRequests, color: '#F05460' },
      { name: 'Server Errors (5xx)', value: activeMetrics.errorRequests, color: '#899693' },
    ].filter((item) => item.value > 0)
  }, [activeMetrics])

  // Sorted Top Consumers List
  const sortedConsumers = useMemo(() => {
    if (!topConsumers) return []
    const copy = [...topConsumers]
    copy.sort((a, b) => {
      let valA = 0
      let valB = 0
      if (sortField === 'total') {
        valA = a.totalRequests
        valB = b.totalRequests
      } else if (sortField === 'allowed') {
        valA = a.allowedRequests
        valB = b.allowedRequests
      } else if (sortField === 'limited') {
        valA = a.rateLimitedRequests
        valB = b.rateLimitedRequests
      } else if (sortField === 'rate') {
        valA = a.rateLimitDenialPercentage
        valB = b.rateLimitDenialPercentage
      }
      return sortOrder === 'asc' ? valA - valB : valB - valA
    })
    return copy
  }, [topConsumers, sortField, sortOrder])

  // Toggle sort direction
  const handleSort = (field: 'total' | 'allowed' | 'limited' | 'rate') => {
    if (sortField === field) {
      setSortOrder((prev) => (prev === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortOrder('desc')
    }
  }

  // Endpoints distribution bar chart data (when consumer is selected)
  const endpointChartData = useMemo(() => {
    if (!consumerAnalytics?.requestsByEndpoint) return []
    return Object.entries(consumerAnalytics.requestsByEndpoint).map(([path, count]) => ({
      path,
      requests: count,
    }))
  }, [consumerAnalytics])

  return (
    <div className="space-y-6">
      {/* 1. Header & Investigation Control Toolbar */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-silver-frost">Telemetry & Usage Analytics</h1>
          <p className="text-xs text-titanium mt-1 font-mono">
            Investigate request throughput, rate-limit throttling distributions, and temporal latency trends
          </p>
        </div>

        {/* Global Toolbar: DateRange + Refresh */}
        <div className="flex flex-wrap items-center gap-2 sm:gap-3">
          <DateRangePicker range={dateRange} onChange={handleDateRangeChange} />
          <Button
            variant="outline"
            size="sm"
            onClick={handleRefresh}
            disabled={isRefreshing}
            className="cursor-pointer"
            title="Refresh analytics data"
            aria-label="Refresh analytics data"
          >
            <RefreshCw className={`w-3.5 h-3.5 mr-1.5 ${isRefreshing ? 'animate-spin text-emerald' : ''}`} />
            <span>Sync</span>
          </Button>
        </div>
      </div>

      {/* 2. Interactive Filter Bar */}
      <div className="p-3.5 bg-surface rounded-xl border border-moss-subtle flex flex-wrap items-center justify-between gap-3 text-xs shadow-card-dark">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1.5 text-titanium font-semibold uppercase tracking-wider text-[11px]">
            <Filter className="w-3.5 h-3.5 text-emerald" />
            <span>Scope Filter:</span>
          </div>

          {/* Target Consumer Selector */}
          <div className="flex items-center gap-1.5">
            <select
              value={selectedConsumerId}
              onChange={(e) => {
                setSelectedConsumerId(e.target.value)
                setEventPage(0)
              }}
              className="px-2.5 py-1.5 bg-input border border-moss/60 rounded-md text-silver-frost text-xs focus:outline-none focus:ring-1 focus:ring-emerald/50 cursor-pointer"
              aria-label="Filter by Consumer"
            >
              <option value="ALL">All Consumers (Aggregated Control Plane)</option>
              {consumers.map((c: ConsumerDto) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.email})
                </option>
              ))}
            </select>
          </div>

          {/* Outcome Filter */}
          <div className="flex items-center gap-1.5">
            <select
              value={outcomeFilter}
              onChange={(e) => setOutcomeFilter(e.target.value)}
              className="px-2.5 py-1.5 bg-input border border-moss/60 rounded-md text-silver-frost text-xs focus:outline-none focus:ring-1 focus:ring-emerald/50 cursor-pointer"
              aria-label="Filter by HTTP Outcome"
            >
              <option value="ALL">All HTTP Outcomes</option>
              <option value="ALLOWED">Allowed Only (200 OK)</option>
              <option value="RATE_LIMITED">Throttled Only (429 Rate Limited)</option>
              <option value="SCOPE_DENIED">Denied Only (403 Forbidden)</option>
            </select>
          </div>
        </div>

        {/* Selected Scope Indicator */}
        <div className="text-[11px] text-titanium font-mono">
          Target: <span className="text-emerald font-semibold">{activeMetrics.label}</span>
        </div>
      </div>

      {/* 3. Ingestion Architecture Telemetry Notice */}
      <div className="p-3.5 bg-surface-elevated/40 border border-moss-subtle rounded-xl text-xs text-titanium leading-relaxed flex items-start gap-3">
        <Database className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
        <div className="space-y-0.5">
          <strong className="text-silver-frost">Asynchronous Pipeline Telemetry:</strong> Request audit logs stream
          in real-time through Redis Streams (<code className="font-mono text-emerald">stream:gateway:events</code>) and are
          persisted to PostgreSQL in high-throughput micro-batches. Data reflects accurate transaction records within a 1-second batching boundary.
        </div>
      </div>

      {/* 4. Active Scope Overview Stat Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Total Ingress */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Total Ingress</span>
              <div className="p-1.5 bg-surface-elevated text-emerald rounded-md border border-moss-subtle">
                <Activity className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="text-2xl font-bold text-silver-frost mt-1 tabular-nums">
              {overviewLoading || consumerAnalyticsLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                formatNumber(activeMetrics.totalRequests)
              )}
            </div>
            <span className="text-[10px] text-titanium font-mono">Requests in selected timeframe</span>
          </CardContent>
        </Card>

        {/* Allowed Requests */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Allowed Ingress</span>
              <div className="p-1.5 bg-emerald/10 text-emerald rounded-md border border-emerald/30">
                <CheckCircle2 className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="text-2xl font-bold text-emerald mt-1 tabular-nums">
              {overviewLoading || consumerAnalyticsLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                formatNumber(activeMetrics.allowedRequests)
              )}
            </div>
            <span className="text-[10px] text-titanium font-mono">2xx HTTP responses</span>
          </CardContent>
        </Card>

        {/* Rate Limited */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Throttled (429)</span>
              <div className="p-1.5 bg-warning-amber/10 text-warning-amber rounded-md border border-warning-amber/30">
                <AlertOctagon className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="text-2xl font-bold text-warning-amber mt-1 tabular-nums">
              {overviewLoading || consumerAnalyticsLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                formatNumber(activeMetrics.rateLimitedRequests)
              )}
            </div>
            <span className="text-[10px] text-titanium font-mono">Token bucket exhausted</span>
          </CardContent>
        </Card>

        {/* Denial Percentage */}
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Denial Rate</span>
              <div className="p-1.5 bg-surface-elevated text-titanium rounded-md border border-moss-subtle">
                <ShieldAlert className="w-3.5 h-3.5" />
              </div>
            </div>
            <div className="text-2xl font-bold text-silver-frost mt-1 tabular-nums">
              {overviewLoading || consumerAnalyticsLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                formatPercentage(activeMetrics.rateLimitDenialPercentage)
              )}
            </div>
            <span className="text-[10px] text-titanium font-mono">Throttle share</span>
          </CardContent>
        </Card>
      </div>

      {/* 5. Main Charts: TimeSeries Timeline (2 cols) + Outcome Breakdown (1 col) */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Timeline Chart */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <TrendingUp className="w-4 h-4 text-emerald" />
                  Traffic Throughput Timeline
                </CardTitle>
                <CardDescription>Bucketed gateway request throughput by outcome status</CardDescription>
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
              <div className="h-80 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : filteredTimeSeries.length === 0 ? (
              <div className="h-80 flex flex-col items-center justify-center text-titanium text-center p-4">
                <Activity className="w-8 h-8 text-titanium-muted mb-2 animate-pulse" />
                <p className="text-sm font-medium text-silver-frost">No time-series points recorded in this window</p>
                <span className="text-xs text-titanium mt-1">Traffic will plot automatically as requests pass through proxy routes</span>
              </div>
            ) : (
              <div className="h-80 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={filteredTimeSeries} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <defs>
                      <linearGradient id="anAllowedColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#31E99A" stopOpacity={0.35} />
                        <stop offset="95%" stopColor="#31E99A" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="anLimitedColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#E9B35A" stopOpacity={0.35} />
                        <stop offset="95%" stopColor="#E9B35A" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="anDeniedColor" x1="0" y1="0" x2="0" y2="1">
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
                      formatter={(val: unknown, name: unknown) => [
                        formatNumber(typeof val === 'number' ? val : 0),
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
                    <Legend wrapperStyle={{ fontSize: '11px', paddingTop: '10px' }} />
                    <Area
                      type="monotone"
                      name="Allowed (200)"
                      dataKey="allowedRequests"
                      stroke="#31E99A"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#anAllowedColor)"
                    />
                    <Area
                      type="monotone"
                      name="Rate Limited (429)"
                      dataKey="rateLimitedRequests"
                      stroke="#E9B35A"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#anLimitedColor)"
                    />
                    <Area
                      type="monotone"
                      name="Scope Denied (403)"
                      dataKey="scopeDeniedRequests"
                      stroke="#F05460"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#anDeniedColor)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Outcome Breakdown Donut */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <PieIcon className="w-4 h-4 text-emerald" />
              Outcome Distribution
            </CardTitle>
            <CardDescription>Ratio of permitted vs throttled requests in scope</CardDescription>
          </CardHeader>
          <CardContent>
            {overviewLoading ? (
              <div className="h-80 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : outcomeData.length === 0 ? (
              <div className="h-80 flex flex-col items-center justify-center text-titanium text-center p-4">
                <p className="text-sm font-medium text-silver-frost">No outcome events recorded</p>
                <span className="text-xs text-titanium mt-1">Requires active traffic in timeframe</span>
              </div>
            ) : (
              <div className="h-80 w-full flex flex-col items-center justify-center">
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie
                      data={outcomeData}
                      dataKey="value"
                      nameKey="name"
                      cx="50%"
                      cy="50%"
                      innerRadius={55}
                      outerRadius={80}
                      paddingAngle={3}
                    >
                      {outcomeData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={entry.color} stroke="#131A18" strokeWidth={2} />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(val: unknown) => [formatNumber(typeof val === 'number' ? val : 0), 'Requests']}
                      contentStyle={{
                        backgroundColor: '#131A18',
                        color: '#DCE4E1',
                        borderRadius: '0.5rem',
                        border: '1px solid #375B48',
                        fontSize: '12px',
                      }}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="w-full space-y-1.5 mt-2">
                  {outcomeData.map((item) => (
                    <div key={item.name} className="flex items-center justify-between text-xs">
                      <div className="flex items-center gap-2">
                        <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: item.color }} />
                        <span className="text-titanium">{item.name}</span>
                      </div>
                      <span className="font-semibold text-silver-frost tabular-nums">{formatNumber(item.value)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 6. Contextual Section: Specific Consumer Deep-Dive OR Global Ranked Consumers */}
      {selectedConsumerId !== 'ALL' ? (
        <div className="space-y-6">
          {/* Consumer Endpoints Breakdown Bar Chart */}
          {endpointChartData.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2">
                  <Layers className="w-4 h-4 text-emerald" />
                  Request Volume by Endpoint Path
                </CardTitle>
                <CardDescription>Target proxy endpoints invoked by {activeMetrics.label}</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="h-64 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={endpointChartData} layout="vertical" margin={{ top: 10, right: 20, left: 20, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#1E3429" />
                      <XAxis type="number" tick={{ fontSize: 11, fill: '#899693' }} axisLine={{ stroke: '#22382D' }} />
                      <YAxis
                        dataKey="path"
                        type="category"
                        tick={{ fontSize: 11, fill: '#DCE4E1', fontFamily: 'monospace' }}
                        axisLine={{ stroke: '#22382D' }}
                      />
                      <Tooltip
                        formatter={(val: unknown) => [formatNumber(typeof val === 'number' ? val : 0), 'Requests']}
                        contentStyle={{
                          backgroundColor: '#131A18',
                          color: '#DCE4E1',
                          borderRadius: '0.5rem',
                          border: '1px solid #375B48',
                          fontSize: '12px',
                        }}
                      />
                      <Bar dataKey="requests" fill="#31E99A" radius={[0, 4, 4, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Paginated Usage Events Audit Log Table */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <div>
                  <CardTitle className="text-base">Consumer Usage Event Audit Log</CardTitle>
                  <CardDescription>
                    Individual request records draining from Redis Streams for {activeMetrics.label}
                  </CardDescription>
                </div>
                <Badge variant="purple">Page {eventPage + 1}</Badge>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              {consumerUsageLoading ? (
                <div className="p-6 space-y-3">
                  <Skeleton className="h-8 w-full" />
                  <Skeleton className="h-8 w-full" />
                  <Skeleton className="h-8 w-full" />
                </div>
              ) : !consumerUsagePage?.content || consumerUsagePage.content.length === 0 ? (
                <div className="p-8 text-center text-titanium">
                  <p className="text-sm font-medium text-silver-frost">No individual usage events recorded in this period</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm text-silver-frost tabular-nums">
                    <thead className="bg-surface-elevated/80 border-b border-moss-subtle text-[11px] font-semibold text-titanium uppercase tracking-wider">
                      <tr>
                        <th className="px-6 py-3">Timestamp</th>
                        <th className="px-6 py-3">Method</th>
                        <th className="px-6 py-3">Endpoint Path</th>
                        <th className="px-6 py-3">Status</th>
                        <th className="px-6 py-3">Outcome</th>
                        <th className="px-6 py-3">Latency</th>
                        <th className="px-6 py-3">Key Prefix</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-moss-subtle/40 bg-surface">
                      {consumerUsagePage.content.map((evt) => (
                        <tr key={evt.id} className="hover:bg-surface-elevated/50 transition-colors">
                          <td className="px-6 py-3 text-xs text-titanium font-mono">{formatDate(evt.timestamp)}</td>
                          <td className="px-6 py-3">
                            <span className="font-mono text-xs font-bold text-emerald">{evt.httpMethod}</span>
                          </td>
                          <td className="px-6 py-3 text-xs font-mono text-silver-frost">{evt.endpointPath}</td>
                          <td className="px-6 py-3">
                            <Badge
                              variant={
                                evt.httpStatus < 300
                                  ? 'success'
                                  : evt.httpStatus === 429
                                  ? 'warning'
                                  : 'danger'
                              }
                            >
                              {evt.httpStatus}
                            </Badge>
                          </td>
                          <td className="px-6 py-3 text-xs font-semibold">{evt.outcomeCategory}</td>
                          <td className="px-6 py-3 text-xs font-mono text-silver-frost">{evt.latencyMs} ms</td>
                          <td className="px-6 py-3 text-xs font-mono text-titanium">
                            {evt.keyPrefixSnapshot || 'none'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>

                  {/* Pagination Footer */}
                  <div className="px-6 py-3 border-t border-moss-subtle flex items-center justify-between text-xs text-titanium">
                    <span>
                      Showing page {eventPage + 1} of {consumerUsagePage.totalPages || 1}
                    </span>
                    <div className="flex items-center gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setEventPage((p) => Math.max(0, p - 1))}
                        disabled={eventPage === 0}
                      >
                        <ChevronLeft className="w-3.5 h-3.5" />
                        <span>Previous</span>
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setEventPage((p) => p + 1)}
                        disabled={eventPage + 1 >= (consumerUsagePage.totalPages || 1)}
                      >
                        <span>Next</span>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      ) : (
        /* Global Ranked Consumers Table */
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <Activity className="w-4 h-4 text-emerald" />
                  Top Consumers Ranking & Throttle Analysis
                </CardTitle>
                <CardDescription>
                  Highest volume API consumer organizations in active timeframe with multi-column sorting
                </CardDescription>
              </div>
              <Link
                to="/consumers"
                className="text-xs font-semibold text-emerald hover:text-emerald-hover flex items-center gap-1 transition-colors"
              >
                <span>View All Consumers</span>
                <ExternalLink className="w-3.5 h-3.5" />
              </Link>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {topLoading ? (
              <div className="p-6 space-y-3">
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-full" />
              </div>
            ) : sortedConsumers.length === 0 ? (
              <div className="p-10 text-center text-titanium">
                <p className="text-sm font-medium text-silver-frost">No consumer traffic recorded in this timeframe</p>
                <span className="text-xs mt-1 text-titanium-muted">
                  Metrics will display as client tenants invoke gateway routes
                </span>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm text-silver-frost tabular-nums">
                  <thead className="bg-surface-elevated/80 border-b border-moss-subtle text-[11px] font-semibold text-titanium uppercase tracking-wider">
                    <tr>
                      <th className="px-6 py-3">Rank</th>
                      <th className="px-6 py-3">Consumer Organization</th>
                      <th className="px-6 py-3">Subscription</th>
                      <th
                        className="px-6 py-3 cursor-pointer hover:text-silver-frost transition-colors"
                        onClick={() => handleSort('total')}
                      >
                        <div className="flex items-center gap-1">
                          <span>Total Ingress</span>
                          <ArrowUpDown className="w-3 h-3" />
                        </div>
                      </th>
                      <th
                        className="px-6 py-3 cursor-pointer hover:text-silver-frost transition-colors"
                        onClick={() => handleSort('allowed')}
                      >
                        <div className="flex items-center gap-1">
                          <span>Allowed (200)</span>
                          <ArrowUpDown className="w-3 h-3" />
                        </div>
                      </th>
                      <th
                        className="px-6 py-3 cursor-pointer hover:text-silver-frost transition-colors"
                        onClick={() => handleSort('limited')}
                      >
                        <div className="flex items-center gap-1">
                          <span>Throttled (429)</span>
                          <ArrowUpDown className="w-3 h-3" />
                        </div>
                      </th>
                      <th
                        className="px-6 py-3 cursor-pointer hover:text-silver-frost transition-colors"
                        onClick={() => handleSort('rate')}
                      >
                        <div className="flex items-center gap-1">
                          <span>Denial Rate</span>
                          <ArrowUpDown className="w-3 h-3" />
                        </div>
                      </th>
                      <th className="px-6 py-3 text-right">Drilldown</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-moss-subtle/40 bg-surface">
                    {sortedConsumers.map((c, idx) => (
                      <tr key={c.consumerId} className="hover:bg-surface-elevated/50 transition-colors">
                        <td className="px-6 py-3.5 font-bold text-titanium font-mono">#{idx + 1}</td>
                        <td className="px-6 py-3.5">
                          <Link
                            to={`/consumers/${c.consumerId}`}
                            className="font-semibold text-silver-frost hover:text-emerald transition-colors"
                          >
                            {c.consumerName}
                          </Link>
                          <div className="text-xs text-titanium font-mono">{c.consumerEmail}</div>
                        </td>
                        <td className="px-6 py-3.5">
                          <Badge variant="purple">{c.plan}</Badge>
                        </td>
                        <td className="px-6 py-3.5 font-bold text-silver-frost">{formatNumber(c.totalRequests)}</td>
                        <td className="px-6 py-3.5 text-emerald font-medium">{formatNumber(c.allowedRequests)}</td>
                        <td className="px-6 py-3.5 text-warning-amber font-medium">{formatNumber(c.rateLimitedRequests)}</td>
                        <td className="px-6 py-3.5">
                          <span
                            className={`font-semibold ${
                              c.rateLimitDenialPercentage > 15
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
                          <button
                            type="button"
                            onClick={() => setSelectedConsumerId(c.consumerId)}
                            className="text-xs font-medium text-emerald hover:text-emerald-hover cursor-pointer"
                          >
                            Filter Scope &rarr;
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
