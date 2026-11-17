import React from 'react'
import { useQuery } from '@tanstack/react-query'
import { planService } from '@/services/planService'
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatNumber, formatDate } from '@/lib/utils'
import {
  Layers,
  Zap,
  Gauge,
  RefreshCw,
  ShieldCheck,
  CheckCircle2,
} from 'lucide-react'

export const Plans: React.FC = () => {
  const {
    data: plans,
    isLoading,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['plans'],
    queryFn: () => planService.listPlans(),
  })

  // Calculate sustained request throughput (tokens / second)
  const calculateThroughput = (tokens: number, periodSeconds: number) => {
    if (!periodSeconds || periodSeconds <= 0) return '0'
    const rate = tokens / periodSeconds
    return rate >= 1 ? `${rate.toFixed(1)} req/s` : `${tokens} req / ${periodSeconds}s`
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Subscription Plans & Quotas</h1>
          <p className="text-xs text-slate-500 mt-1">
            Distributed Redis Token Bucket configurations governing burst capacities and replenishment policies
          </p>
        </div>
        <div>
          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="p-2 border border-slate-200 bg-white rounded-lg text-slate-600 hover:text-slate-900 hover:bg-slate-50 shadow-xs transition-colors disabled:opacity-50"
            title="Refresh plans"
            aria-label="Refresh plans"
          >
            <RefreshCw className={`w-4 h-4 ${isFetching ? 'animate-spin text-indigo-600' : ''}`} />
          </button>
        </div>
      </div>

      {/* Plan Cards Grid */}
      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <Skeleton className="h-80 w-full rounded-xl" />
          <Skeleton className="h-80 w-full rounded-xl" />
          <Skeleton className="h-80 w-full rounded-xl" />
        </div>
      ) : error ? (
        <div className="p-12 text-center bg-white rounded-xl border border-slate-200">
          <Layers className="w-10 h-10 text-rose-500 mx-auto mb-2" />
          <p className="text-sm font-semibold text-slate-800">Failed to load subscription plans</p>
        </div>
      ) : !plans || plans.length === 0 ? (
        <div className="p-12 text-center bg-white rounded-xl border border-slate-200">
          <p className="text-sm text-slate-500">No active subscription plans found.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {plans.map((plan) => {
            const isEnterprise = plan.name.toUpperCase().includes('ENTERPRISE')
            const isPro = plan.name.toUpperCase().includes('PRO')

            return (
              <Card
                key={plan.id}
                className={`relative flex flex-col justify-between transition-all hover:shadow-md ${
                  isEnterprise
                    ? 'border-indigo-500/80 shadow-indigo-100 ring-1 ring-indigo-500/20'
                    : isPro
                    ? 'border-cyan-400/80 shadow-cyan-100'
                    : 'border-slate-200'
                }`}
              >
                {isEnterprise && (
                  <div className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 py-0.5 bg-indigo-600 text-white font-bold text-[10px] tracking-wider uppercase rounded-full shadow-xs">
                    High Throughput Tier
                  </div>
                )}

                <div>
                  <CardHeader className="pb-4">
                    <div className="flex items-center justify-between">
                      <CardTitle className="text-xl font-bold text-slate-900">{plan.name}</CardTitle>
                      {plan.isActive ? (
                        <Badge variant="success">Active</Badge>
                      ) : (
                        <Badge variant="slate">Inactive</Badge>
                      )}
                    </div>
                    <CardDescription className="text-xs text-slate-600 leading-relaxed mt-1">
                      {plan.description || 'Pre-configured rate limiting tier.'}
                    </CardDescription>
                  </CardHeader>

                  <CardContent className="space-y-5 pt-2">
                    {/* Capacity KPI */}
                    <div className="p-4 bg-slate-50 border border-slate-200/60 rounded-xl space-y-1">
                      <div className="flex items-center justify-between text-xs text-slate-500">
                        <span className="flex items-center gap-1 font-semibold">
                          <Zap className="w-3.5 h-3.5 text-indigo-600" />
                          Burst Capacity
                        </span>
                        <span className="font-mono">{formatNumber(plan.bucketCapacity)} tokens</span>
                      </div>
                      <div className="text-2xl font-bold text-slate-900">
                        {formatNumber(plan.bucketCapacity)}
                        <span className="text-xs font-normal text-slate-500 ml-1">max tokens</span>
                      </div>
                    </div>

                    {/* Specifications List */}
                    <div className="space-y-3 text-xs text-slate-600">
                      <div className="flex items-start gap-2.5">
                        <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0 mt-0.5" />
                        <div>
                          <strong>Refill Quota:</strong> {formatNumber(plan.refillTokens)} tokens every{' '}
                          {plan.refillPeriodSeconds} second(s)
                        </div>
                      </div>

                      <div className="flex items-start gap-2.5">
                        <Gauge className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
                        <div>
                          <strong>Sustained Rate:</strong> ~
                          {calculateThroughput(plan.refillTokens, plan.refillPeriodSeconds)}
                        </div>
                      </div>

                      <div className="flex items-start gap-2.5">
                        <ShieldCheck className="w-4 h-4 text-cyan-600 shrink-0 mt-0.5" />
                        <div>
                          <strong>Atomic Redis Lua Script:</strong> Concurrency-safe token refills with sub-millisecond
                          latency
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </div>

                <CardFooter className="bg-slate-50/60 border-t border-slate-100 text-[11px] text-slate-400 flex justify-between items-center py-3">
                  <span>ID: {plan.id.substring(0, 8)}...</span>
                  <span>Updated: {formatDate(plan.updatedAt)}</span>
                </CardFooter>
              </Card>
            )
          })}
        </div>
      )}

      {/* Architecture Note */}
      <div className="p-4 bg-indigo-50/60 border border-indigo-100 rounded-xl text-xs text-indigo-900 leading-relaxed flex items-start gap-3">
        <ShieldCheck className="w-5 h-5 text-indigo-600 shrink-0 mt-0.5" />
        <div>
          <strong className="font-semibold">Engine Guarantee:</strong> Gatekeeper utilizes atomic Redis Lua scripts to
          ensure deterministic token evaluation across horizontally-scaled gateway nodes. When tokens reach 0,
          requests are immediately halted with <code>HTTP 429 Too Many Requests</code> and headers indicating{' '}
          <code>X-RateLimit-Limit</code>, <code>X-RateLimit-Remaining</code>, and <code>Retry-After</code>.
        </div>
      </div>
    </div>
  )
}
