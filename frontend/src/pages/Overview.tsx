import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { analyticsService } from '@/services/analyticsService'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { DateRangePicker, DateRange } from '@/components/ui/DateRangePicker'
import { Skeleton } from '@/components/ui/Skeleton'
import { Badge } from '@/components/ui/Badge'
import { formatNumber, formatPercentage, formatDate } from '@/lib/utils'
import {
  Activity,
  CheckCircle,
  AlertOctagon,
  ShieldAlert,
  Percent,
  ArrowUpRight,
  RefreshCw,
  TrendingUp,
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
  const [dateRange, setDateRange] = useState<DateRange>(() => {
    const end = new Date()
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000)
    return {
      start: start.toISOString(),
      end: end.toISOString(),
      preset: '24h',
    }
  })

  // 1. Overview Query
  const {
    data: overview,
    isLoading: overviewLoading,
    refetch: refetchOverview,
    isFetching: overviewFetching,
  } = useQuery({
    queryKey: ['analytics', 'overview', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getOverview(dateRange.start, dateRange.end),
  })

  // 2. TimeSeries Query
  const {
    data: timeSeries,
    isLoading: timeSeriesLoading,
    error: timeSeriesError,
    refetch: refetchTimeSeries,
  } = useQuery({
    queryKey: ['analytics', 'timeseries', dateRange.start, dateRange.end],
    queryFn: () =>
      analyticsService.getTimeSeries(
        dateRange.start,
        dateRange.end,
        dateRange.preset === '30d' ? 'daily' : 'hourly'
      ),
  })

  // 3. Top Consumers Query
  const {
    data: topConsumers,
    isLoading: topConsumersLoading,
    error: topConsumersError,
    refetch: refetchTopConsumers,
  } = useQuery({
    queryKey: ['analytics', 'topConsumers', dateRange.start, dateRange.end],
    queryFn: () => analyticsService.getTopConsumers(dateRange.start, dateRange.end, 5),
  })

  const handleRefresh = () => {
    refetchOverview()
    refetchTimeSeries()
    refetchTopConsumers()
  }

  // Transform usage by plan for chart
  const planData = React.useMemo(() => {
    if (!overview?.usageByPlan) return []
    return Object.entries(overview.usageByPlan).map(([name, count]) => ({
      name,
      requests: count,
    }))
  }, [overview])

  const planColors = ['#4f46e5', '#06b6d4', '#8b5cf6', '#f59e0b']

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-2 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Gateway Overview</h1>
          <p className="text-xs text-slate-500 mt-1">
            Real-time traffic telemetry and distributed token bucket rate limit enforcement
          </p>
        </div>
        <div className="flex items-center gap-3">
          <DateRangePicker range={dateRange} onChange={setDateRange} />
          <button
            onClick={handleRefresh}
            disabled={overviewFetching}
            className="p-2 border border-slate-200 bg-white rounded-lg text-slate-600 hover:text-slate-900 hover:bg-slate-50 shadow-xs transition-colors disabled:opacity-50"
            title="Refresh analytics data"
            aria-label="Refresh analytics data"
          >
            <RefreshCw className={`w-4 h-4 ${overviewFetching ? 'animate-spin text-indigo-600' : ''}`} />
          </button>
        </div>
      </div>

      {/* KPI Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        {/* Total Requests */}
        <Card className="hover:border-slate-300 transition-colors">
          <CardContent className="p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Total Traffic</span>
              <div className="p-2 bg-indigo-50 text-indigo-600 rounded-lg">
                <Activity className="w-4 h-4" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-8 w-24" />
              ) : (
                <div className="text-2xl font-bold text-slate-900">{formatNumber(overview?.totalRequests)}</div>
              )}
              <span className="text-[11px] text-slate-400">Total gateway ingress</span>
            </div>
          </CardContent>
        </Card>

        {/* Allowed Requests */}
        <Card className="hover:border-slate-300 transition-colors">
          <CardContent className="p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Allowed (200)</span>
              <div className="p-2 bg-emerald-50 text-emerald-600 rounded-lg">
                <CheckCircle className="w-4 h-4" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-8 w-24" />
              ) : (
                <div className="text-2xl font-bold text-emerald-600">{formatNumber(overview?.allowedRequests)}</div>
              )}
              <span className="text-[11px] text-slate-400">Within token quota</span>
            </div>
          </CardContent>
        </Card>

        {/* Rate Limited Requests */}
        <Card className="hover:border-slate-300 transition-colors">
          <CardContent className="p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Rate Limited (429)</span>
              <div className="p-2 bg-amber-50 text-amber-600 rounded-lg">
                <AlertOctagon className="w-4 h-4" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-8 w-24" />
              ) : (
                <div className="text-2xl font-bold text-amber-600">
                  {formatNumber(overview?.rateLimitedRequests)}
                </div>
              )}
              <span className="text-[11px] text-slate-400">Token bucket empty</span>
            </div>
          </CardContent>
        </Card>

        {/* Scope Denied Requests */}
        <Card className="hover:border-slate-300 transition-colors">
          <CardContent className="p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Scope Denied (403)</span>
              <div className="p-2 bg-rose-50 text-rose-600 rounded-lg">
                <ShieldAlert className="w-4 h-4" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-8 w-24" />
              ) : (
                <div className="text-2xl font-bold text-rose-600">
                  {formatNumber(overview?.scopeDeniedRequests)}
                </div>
              )}
              <span className="text-[11px] text-slate-400">Unauthorized key scope</span>
            </div>
          </CardContent>
        </Card>

        {/* Denial Percentage */}
        <Card className="hover:border-slate-300 transition-colors">
          <CardContent className="p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Denial Rate</span>
              <div className="p-2 bg-purple-50 text-purple-600 rounded-lg">
                <Percent className="w-4 h-4" />
              </div>
            </div>
            <div className="mt-2">
              {overviewLoading ? (
                <Skeleton className="h-8 w-24" />
              ) : (
                <div className="text-2xl font-bold text-purple-700">
                  {formatPercentage(overview?.rateLimitDenialPercentage)}
                </div>
              )}
              <span className="text-[11px] text-slate-400">Quota throttle share</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Main Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Time Series Traffic Chart (2 cols) */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <TrendingUp className="w-4 h-4 text-indigo-600" />
                  Traffic Volume Trend
                </CardTitle>
                <CardDescription>Bucketed request throughput (Allowed vs Rate-Limited)</CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {timeSeriesLoading ? (
              <div className="h-72 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : timeSeriesError ? (
              <div className="h-72 flex flex-col items-center justify-center text-slate-400">
                <p className="text-sm font-medium">Failed to load time series data</p>
                <span className="text-xs mt-1">Please verify backend connectivity</span>
              </div>
            ) : !timeSeries || timeSeries.length === 0 ? (
              <div className="h-72 flex flex-col items-center justify-center text-slate-400">
                <Activity className="w-8 h-8 text-slate-300 mb-2" />
                <p className="text-sm font-medium">No traffic recorded in this time window</p>
                <span className="text-xs text-slate-400 mt-1">Send gateway requests with valid API keys to view metrics</span>
              </div>
            ) : (
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={timeSeries} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <defs>
                      <linearGradient id="allowedGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#10b981" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#10b981" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="limitedGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#f59e0b" stopOpacity={0.0} />
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
                      formatter={(value: unknown, name: unknown) => [
                        formatNumber(typeof value === 'number' ? value : 0),
                        name === 'allowedRequests' ? 'Allowed' : name === 'rateLimitedRequests' ? 'Rate Limited' : String(name || ''),
                      ]}
                      labelFormatter={(label) => formatDate(String(label))}
                      contentStyle={{
                        backgroundColor: '#0f172a',
                        color: '#f8fafc',
                        borderRadius: '0.5rem',
                        border: 'none',
                        fontSize: '12px',
                      }}
                    />
                    <Area
                      type="monotone"
                      dataKey="allowedRequests"
                      stroke="#10b981"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#allowedGrad)"
                    />
                    <Area
                      type="monotone"
                      dataKey="rateLimitedRequests"
                      stroke="#f59e0b"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#limitedGrad)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Plan Breakdown Bar Chart (1 col) */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Traffic by Subscription Plan</CardTitle>
            <CardDescription>Volume distribution by active tier</CardDescription>
          </CardHeader>
          <CardContent>
            {overviewLoading ? (
              <div className="h-72 flex items-center justify-center">
                <Skeleton className="h-full w-full rounded-lg" />
              </div>
            ) : planData.length === 0 ? (
              <div className="h-72 flex flex-col items-center justify-center text-slate-400 text-center p-4">
                <p className="text-sm font-medium">No tier data available</p>
                <span className="text-xs text-slate-400 mt-1">Metrics will appear once consumers invoke endpoints</span>
              </div>
            ) : (
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={planData} layout="vertical" margin={{ top: 10, right: 20, left: 20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f1f5f9" />
                    <XAxis type="number" tick={{ fontSize: 11, fill: '#64748b' }} axisLine={{ stroke: '#e2e8f0' }} />
                    <YAxis
                      dataKey="name"
                      type="category"
                      tick={{ fontSize: 11, fill: '#334155', fontWeight: 600 }}
                      axisLine={{ stroke: '#e2e8f0' }}
                    />
                    <Tooltip
                      formatter={(value: unknown) => [formatNumber(typeof value === 'number' ? value : 0), 'Requests']}
                      contentStyle={{
                        backgroundColor: '#0f172a',
                        color: '#f8fafc',
                        borderRadius: '0.5rem',
                        border: 'none',
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

      {/* Top Consumers Table */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-base">Top Consumers by Volume</CardTitle>
              <CardDescription>Active consumers ranked by gateway requests in selected window</CardDescription>
            </div>
            <Link
              to="/consumers"
              className="text-xs font-semibold text-indigo-600 hover:text-indigo-700 flex items-center gap-1"
            >
              View All Consumers
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
            <div className="p-8 text-center text-slate-400">
              <p className="text-sm">Unable to load consumer rankings</p>
            </div>
          ) : !topConsumers || topConsumers.length === 0 ? (
            <div className="p-8 text-center text-slate-400">
              <p className="text-sm font-medium">No consumer traffic recorded in this period</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-700">
                <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3">Consumer</th>
                    <th className="px-6 py-3">Plan</th>
                    <th className="px-6 py-3">Total Requests</th>
                    <th className="px-6 py-3">Allowed</th>
                    <th className="px-6 py-3">Rate Limited</th>
                    <th className="px-6 py-3">Denial Rate</th>
                    <th className="px-6 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 bg-white">
                  {topConsumers.map((c) => (
                    <tr key={c.consumerId} className="hover:bg-slate-50/60 transition-colors">
                      <td className="px-6 py-3.5 font-medium text-slate-900">
                        <Link
                          to={`/consumers/${c.consumerId}`}
                          className="hover:text-indigo-600 transition-colors font-semibold"
                        >
                          {c.consumerName}
                        </Link>
                        <div className="text-xs text-slate-400 font-mono">{c.consumerEmail}</div>
                      </td>
                      <td className="px-6 py-3.5">
                        <Badge variant="purple">{c.plan}</Badge>
                      </td>
                      <td className="px-6 py-3.5 font-semibold text-slate-900">{formatNumber(c.totalRequests)}</td>
                      <td className="px-6 py-3.5 text-emerald-600 font-medium">{formatNumber(c.allowedRequests)}</td>
                      <td className="px-6 py-3.5 text-amber-600 font-medium">{formatNumber(c.rateLimitedRequests)}</td>
                      <td className="px-6 py-3.5">
                        <span
                          className={`font-medium ${
                            c.rateLimitDenialPercentage > 20
                              ? 'text-rose-600'
                              : c.rateLimitDenialPercentage > 0
                              ? 'text-amber-600'
                              : 'text-slate-500'
                          }`}
                        >
                          {formatPercentage(c.rateLimitDenialPercentage)}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 text-right">
                        <Link
                          to={`/consumers/${c.consumerId}`}
                          className="text-xs font-medium text-indigo-600 hover:text-indigo-800"
                        >
                          Inspect &rarr;
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
