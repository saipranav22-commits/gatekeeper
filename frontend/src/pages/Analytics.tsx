import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { analyticsService } from '@/services/analyticsService'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { DateRangePicker, DateRange } from '@/components/ui/DateRangePicker'
import { Skeleton } from '@/components/ui/Skeleton'
import { Badge } from '@/components/ui/Badge'
import { formatNumber, formatPercentage, formatDate } from '@/lib/utils'
import {
  TrendingUp,
  Activity,
  Info,
  RefreshCw,
  PieChart as PieIcon,
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
} from 'recharts'

export const Analytics: React.FC = () => {
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

  // 1. Overview Query
  const {
    data: overview,
    isLoading: overviewLoading,
    refetch: refetchOverview,
    isFetching,
  } = useQuery({
    queryKey: ['analytics', 'overview', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getOverview(dateRange.start, dateRange.end),
  })

  // 2. TimeSeries Query
  const {
    data: timeSeries,
    isLoading: timeSeriesLoading,
    refetch: refetchTimeSeries,
  } = useQuery({
    queryKey: ['analytics', 'timeseries', dateRange.start, dateRange.end, granularity],
    queryFn: () => analyticsService.getTimeSeries(dateRange.start, dateRange.end, granularity),
  })

  // 3. Top Consumers Query
  const {
    data: topConsumers,
    isLoading: topLoading,
    refetch: refetchTop,
  } = useQuery({
    queryKey: ['analytics', 'topConsumers', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getTopConsumers(dateRange.start, dateRange.end, 10),
  })

  const handleRefresh = () => {
    refetchOverview()
    refetchTimeSeries()
    refetchTop()
  }

  // Outcome distribution pie chart data
  const outcomeData = React.useMemo(() => {
    if (!overview) return []
    return [
      { name: 'Allowed (200)', value: overview.allowedRequests, color: '#10b981' },
      { name: 'Rate Limited (429)', value: overview.rateLimitedRequests, color: '#f59e0b' },
      { name: 'Scope Denied (403)', value: overview.scopeDeniedRequests, color: '#f43f5e' },
      { name: 'Error Invocations', value: overview.errorRequests, color: '#8b5cf6' },
    ].filter((item) => item.value > 0)
  }, [overview])

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-2 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Traffic Analytics & Auditing</h1>
          <p className="text-xs text-slate-500 mt-1">
            Aggregated gateway throughput, quota throttle ratios, and temporal distribution
          </p>
        </div>
        <div className="flex items-center gap-3">
          <DateRangePicker range={dateRange} onChange={setDateRange} />
          <button
            onClick={handleRefresh}
            disabled={isFetching}
            className="p-2 border border-slate-200 bg-white rounded-lg text-slate-600 hover:text-slate-900 hover:bg-slate-50 shadow-xs transition-colors disabled:opacity-50"
            title="Refresh analytics data"
            aria-label="Refresh analytics data"
          >
            <RefreshCw className={`w-4 h-4 ${isFetching ? 'animate-spin text-indigo-600' : ''}`} />
          </button>
        </div>
      </div>

      {/* Asynchronous Ingestion Architecture Notice */}
      <div className="p-4 bg-slate-900 text-slate-200 rounded-xl text-xs leading-relaxed flex items-start gap-3 border border-slate-800">
        <Info className="w-5 h-5 text-indigo-400 shrink-0 mt-0.5" />
        <div>
          <strong className="text-white">Pipeline Architecture:</strong> To protect gateway response latency, request
          telemetry is dispatched asynchronously to Redis Streams (<code>stream:gateway:events</code>). A background batch
          worker drains the stream and inserts records into PostgreSQL in durable transactions. A small processing window
          (1000ms) may apply to newly submitted traffic.
        </div>
      </div>

      {/* Overview Stat Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="p-5">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Total Ingress</span>
            <div className="text-2xl font-bold text-slate-900 mt-1">
              {overviewLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(overview?.totalRequests)}
            </div>
            <span className="text-[11px] text-slate-400">Total requests logged</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Allowed Ingress</span>
            <div className="text-2xl font-bold text-emerald-600 mt-1">
              {overviewLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(overview?.allowedRequests)}
            </div>
            <span className="text-[11px] text-slate-400">HTTP 200 responses</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Rate Limits Enforced</span>
            <div className="text-2xl font-bold text-amber-600 mt-1">
              {overviewLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(overview?.rateLimitedRequests)}
            </div>
            <span className="text-[11px] text-slate-400">HTTP 429 quota exhaustion</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Denial Percentage</span>
            <div className="text-2xl font-bold text-purple-700 mt-1">
              {overviewLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : (
                formatPercentage(overview?.rateLimitDenialPercentage)
              )}
            </div>
            <span className="text-[11px] text-slate-400">429 throttle percentage</span>
          </CardContent>
        </Card>
      </div>

      {/* Charts Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Time-Series Chart (2 cols) */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <TrendingUp className="w-4 h-4 text-indigo-600" />
                  Traffic Throughput Timeline
                </CardTitle>
                <CardDescription>Gateway requests over time by outcome status</CardDescription>
              </div>
              <div className="flex items-center gap-1 bg-slate-100 p-0.5 rounded-lg text-xs">
                <button
                  type="button"
                  onClick={() => setGranularity('hourly')}
                  className={`px-2.5 py-1 rounded-md font-medium transition-all ${
                    granularity === 'hourly' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  Hourly
                </button>
                <button
                  type="button"
                  onClick={() => setGranularity('daily')}
                  className={`px-2.5 py-1 rounded-md font-medium transition-all ${
                    granularity === 'daily' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900'
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
            ) : !timeSeries || timeSeries.length === 0 ? (
              <div className="h-80 flex flex-col items-center justify-center text-slate-400">
                <Activity className="w-8 h-8 text-slate-300 mb-2" />
                <p className="text-sm font-medium">No time-series points recorded in this window</p>
              </div>
            ) : (
              <div className="h-80 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={timeSeries} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <defs>
                      <linearGradient id="allowedColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#10b981" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#10b981" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="rateLimitedColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#f59e0b" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="scopeDeniedColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#f43f5e" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#f43f5e" stopOpacity={0.0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                    <XAxis
                      dataKey="bucket"
                      tickFormatter={(val) => formatDate(val).split(',')[0]}
                      tick={{ fontSize: 11, fill: '#64748b' }}
                      axisLine={{ stroke: '#e2e8f0' }}
                    />
                    <YAxis tick={{ fontSize: 11, fill: '#64748b' }} axisLine={{ stroke: '#e2e8f0' }} />
                    <Tooltip
                      formatter={(val: unknown, name: unknown) => [
                        formatNumber(typeof val === 'number' ? val : 0),
                        name === 'allowedRequests'
                          ? 'Allowed'
                          : name === 'rateLimitedRequests'
                          ? 'Rate Limited'
                          : name === 'scopeDeniedRequests'
                          ? 'Scope Denied'
                          : String(name || ''),
                      ]}
                      labelFormatter={(label) => formatDate(String(label))}
                      contentStyle={{
                        backgroundColor: '#0f172a',
                        color: '#f8fafc',
                        borderRadius: '0.5rem',
                        fontSize: '12px',
                        border: 'none',
                      }}
                    />
                    <Legend wrapperStyle={{ fontSize: '11px', paddingTop: '10px' }} />
                    <Area
                      type="monotone"
                      name="Allowed (200)"
                      dataKey="allowedRequests"
                      stroke="#10b981"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#allowedColor)"
                    />
                    <Area
                      type="monotone"
                      name="Rate Limited (429)"
                      dataKey="rateLimitedRequests"
                      stroke="#f59e0b"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#rateLimitedColor)"
                    />
                    <Area
                      type="monotone"
                      name="Scope Denied (403)"
                      dataKey="scopeDeniedRequests"
                      stroke="#f43f5e"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#scopeDeniedColor)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Outcome Breakdown (1 col) */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <PieIcon className="w-4 h-4 text-indigo-600" />
              Gateway Outcome Distribution
            </CardTitle>
            <CardDescription>Ratio of granted vs throttled requests</CardDescription>
          </CardHeader>
          <CardContent>
            {overviewLoading ? (
              <div className="h-80 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : outcomeData.length === 0 ? (
              <div className="h-80 flex flex-col items-center justify-center text-slate-400 text-center p-4">
                <p className="text-sm font-medium">No outcome data recorded</p>
              </div>
            ) : (
              <div className="h-80 w-full flex flex-col items-center justify-center">
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie
                      data={outcomeData}
                      dataKey="value"
                      nameKey="name"
                      cx="50%"
                      cy="50%"
                      innerRadius={55}
                      outerRadius={85}
                      paddingAngle={3}
                    >
                      {outcomeData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={entry.color} />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(val: unknown) => [formatNumber(typeof val === 'number' ? val : 0), 'Requests']}
                      contentStyle={{
                        backgroundColor: '#0f172a',
                        color: '#f8fafc',
                        borderRadius: '0.5rem',
                        fontSize: '12px',
                        border: 'none',
                      }}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="w-full space-y-1 mt-2">
                  {outcomeData.map((item) => (
                    <div key={item.name} className="flex items-center justify-between text-xs">
                      <div className="flex items-center gap-2">
                        <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: item.color }} />
                        <span className="text-slate-600">{item.name}</span>
                      </div>
                      <span className="font-semibold text-slate-900">{formatNumber(item.value)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Top Consumers Ranked */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Top Consumers Ranking</CardTitle>
          <CardDescription>Highest volume API consumer organizations in active timeframe</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          {topLoading ? (
            <div className="p-6 space-y-3">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : !topConsumers || topConsumers.length === 0 ? (
            <div className="p-10 text-center text-slate-400">
              <p className="text-sm font-medium">No consumer traffic recorded in this timeframe</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-700">
                <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3">Rank</th>
                    <th className="px-6 py-3">Consumer</th>
                    <th className="px-6 py-3">Subscription</th>
                    <th className="px-6 py-3">Total Requests</th>
                    <th className="px-6 py-3">Allowed</th>
                    <th className="px-6 py-3">Rate Limited</th>
                    <th className="px-6 py-3">Denial Rate</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 bg-white">
                  {topConsumers.map((c, idx) => (
                    <tr key={c.consumerId} className="hover:bg-slate-50/60">
                      <td className="px-6 py-3.5 font-bold text-slate-400">#{idx + 1}</td>
                      <td className="px-6 py-3.5">
                        <div className="font-semibold text-slate-900">{c.consumerName}</div>
                        <div className="text-xs text-slate-400 font-mono">{c.consumerEmail}</div>
                      </td>
                      <td className="px-6 py-3.5">
                        <Badge variant="purple">{c.plan}</Badge>
                      </td>
                      <td className="px-6 py-3.5 font-bold text-slate-900">{formatNumber(c.totalRequests)}</td>
                      <td className="px-6 py-3.5 font-medium text-emerald-600">{formatNumber(c.allowedRequests)}</td>
                      <td className="px-6 py-3.5 font-medium text-amber-600">{formatNumber(c.rateLimitedRequests)}</td>
                      <td className="px-6 py-3.5">
                        <span
                          className={`font-semibold ${
                            c.rateLimitDenialPercentage > 15 ? 'text-rose-600' : 'text-slate-600'
                          }`}
                        >
                          {formatPercentage(c.rateLimitDenialPercentage)}
                        </span>
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
