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
  Clock,
  Cpu,
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
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-bold tracking-tight text-silver-frost">
              Subscription Plans & Rate Limits
            </h1>
            <Badge variant="slate" className="border-moss text-titanium text-[10px]">
              Platform Quotas
            </Badge>
          </div>
          <p className="text-xs text-titanium mt-1">
            Distributed Redis Token Bucket configurations governing burst capacities and replenishment policies
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-titanium hover:text-silver-frost bg-surface border border-moss/60 hover:border-emerald/40 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            title="Refresh plans"
            aria-label="Refresh plans"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin text-emerald' : ''}`} />
            <span>Refresh</span>
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
        <Card className="p-12 text-center border-crimson/40 bg-surface/50">
          <Layers className="w-10 h-10 text-crimson mx-auto mb-2" />
          <p className="text-sm font-semibold text-silver-frost">Failed to load subscription plans</p>
          <p className="text-xs text-titanium-muted mt-1">
            Unable to communicate with the Gateway control plane.
          </p>
        </Card>
      ) : !plans || plans.length === 0 ? (
        <Card className="p-12 text-center border-moss/30 bg-surface/50">
          <p className="text-sm text-titanium">No active subscription plans found.</p>
        </Card>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {plans.map((plan) => {
            const isEnterprise = plan.name.toUpperCase().includes('ENTERPRISE')
            const isPro = plan.name.toUpperCase().includes('GROWTH') || plan.name.toUpperCase().includes('PRO')

            return (
              <Card
                key={plan.id}
                className={`relative flex flex-col justify-between transition-all duration-200 hover:border-moss bg-surface ${
                  isEnterprise
                    ? 'border-emerald/40 shadow-emerald-sm ring-1 ring-emerald/20'
                    : isPro
                    ? 'border-moss/70'
                    : 'border-moss/40'
                }`}
              >
                <div>
                  <CardHeader className="pb-4">
                    <div className="flex items-center justify-between gap-2 mb-2 min-h-[22px]">
                      <div>
                        {isEnterprise ? (
                          <span
                            data-testid="enterprise-highlight-badge"
                            className="inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-mono font-bold tracking-wider uppercase bg-emerald/20 border border-emerald/50 text-emerald shadow-emerald-sm whitespace-nowrap"
                          >
                            High-Throughput Tier
                          </span>
                        ) : (
                          <span className="inline-block h-5" aria-hidden="true" />
                        )}
                      </div>
                      {plan.isActive ? (
                        <Badge variant="success">Active</Badge>
                      ) : (
                        <Badge variant="slate">Inactive</Badge>
                      )}
                    </div>
                    <CardTitle className="text-lg font-bold text-silver-frost">{plan.name}</CardTitle>
                    <CardDescription className="text-xs text-titanium leading-relaxed mt-1">
                      {plan.description || 'Pre-configured rate limiting tier.'}
                    </CardDescription>
                  </CardHeader>

                  <CardContent className="space-y-4 pt-1">
                    {/* Capacity KPI Card */}
                    <div className="p-3.5 bg-surface-elevated/70 border border-moss/40 rounded-xl space-y-1">
                      <div className="flex items-center justify-between text-xs text-titanium">
                        <span className="flex items-center gap-1.5 font-medium text-titanium">
                          <Zap className="w-3.5 h-3.5 text-emerald" />
                          Burst Capacity
                        </span>
                        <span className="font-mono text-emerald font-semibold">
                          {formatNumber(plan.bucketCapacity)} tokens
                        </span>
                      </div>
                      <div className="text-2xl font-bold font-mono text-silver-frost">
                        {formatNumber(plan.bucketCapacity)}
                        <span className="text-xs font-normal text-titanium-muted ml-1.5 font-sans">
                          max burst tokens
                        </span>
                      </div>
                    </div>

                    {/* Specifications List */}
                    <div className="space-y-2.5 text-xs text-titanium">
                      <div className="flex items-start gap-2.5">
                        <CheckCircle2 className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
                        <div>
                          <span className="text-silver-frost font-medium">Refill Quota:</span>{' '}
                          <span className="font-mono">{formatNumber(plan.refillTokens)}</span> tokens every{' '}
                          <span className="font-mono">{plan.refillPeriodSeconds}</span>s
                        </div>
                      </div>

                      <div className="flex items-start gap-2.5">
                        <Gauge className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
                        <div>
                          <span className="text-silver-frost font-medium">Sustained Rate:</span> ~
                          <span className="font-mono text-silver-frost">
                            {calculateThroughput(plan.refillTokens, plan.refillPeriodSeconds)}
                          </span>
                        </div>
                      </div>

                      <div className="flex items-start gap-2.5">
                        <Cpu className="w-4 h-4 text-titanium-muted shrink-0 mt-0.5" />
                        <div className="text-titanium-muted">
                          <span className="text-titanium font-medium">Atomic Lua Refill:</span> Redis Token Bucket
                          algorithm
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </div>

                <CardFooter className="bg-surface-elevated/40 border-t border-moss/30 text-[11px] font-mono text-titanium-muted flex justify-between items-center py-2.5 px-4 mt-4">
                  <span title={plan.id}>ID: {plan.id.substring(0, 8)}...</span>
                  <span className="flex items-center gap-1">
                    <Clock className="w-3 h-3 text-titanium-muted" />
                    {formatDate(plan.updatedAt)}
                  </span>
                </CardFooter>
              </Card>
            )
          })}
        </div>
      )}

      {/* Plan Rules Comparison Matrix */}
      {plans && plans.length > 0 && (
        <Card className="bg-surface border border-moss/40 overflow-hidden">
          <CardHeader className="border-b border-moss/30 bg-surface-elevated/40 pb-3">
            <CardTitle className="text-sm font-semibold text-silver-frost">
              Subscription Plan Comparison Matrix
            </CardTitle>
            <CardDescription className="text-xs text-titanium">
              Technical limits enforced by Gatekeeper gateway filters across upstream routes
            </CardDescription>
          </CardHeader>
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-moss/30 bg-surface-elevated/60 text-titanium font-medium">
                  <th className="py-2.5 px-4">Plan Tier</th>
                  <th className="py-2.5 px-4">Burst Bucket</th>
                  <th className="py-2.5 px-4">Replenishment</th>
                  <th className="py-2.5 px-4">Refill Period</th>
                  <th className="py-2.5 px-4">Sustained Throughput</th>
                  <th className="py-2.5 px-4">Enforcement Engine</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-moss/20 text-silver-frost">
                {plans.map((p) => (
                  <tr key={p.id} className="hover:bg-surface-elevated/40 transition-colors">
                    <td className="py-3 px-4 font-semibold text-emerald">{p.name}</td>
                    <td className="py-3 px-4 font-mono">{formatNumber(p.bucketCapacity)} tokens</td>
                    <td className="py-3 px-4 font-mono">{formatNumber(p.refillTokens)} tokens</td>
                    <td className="py-3 px-4 font-mono">{p.refillPeriodSeconds}s</td>
                    <td className="py-3 px-4 font-mono font-medium text-silver-frost">
                      ~{calculateThroughput(p.refillTokens, p.refillPeriodSeconds)}
                    </td>
                    <td className="py-3 px-4 text-titanium">Redis 7 Token Bucket (Lua)</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Technical Architecture Note */}
      <div className="p-4 bg-surface border border-emerald/20 rounded-xl text-xs text-titanium leading-relaxed flex items-start gap-3">
        <ShieldCheck className="w-5 h-5 text-emerald shrink-0 mt-0.5" />
        <div>
          <strong className="font-semibold text-silver-frost">Distributed Runtime Quota Guarantees:</strong> Gatekeeper
          executes atomic Redis Lua scripts ensuring concurrency-safe token consumption across horizontally-scaled
          gateway instances. If available tokens reach 0, requests are halted at the edge with{' '}
          <code className="text-crimson font-mono bg-canvas px-1 py-0.5 rounded border border-moss/40">
            HTTP 429 Too Many Requests
          </code>
          , accompanied by standard <code className="text-emerald font-mono">X-RateLimit-Limit</code>,{' '}
          <code className="text-emerald font-mono">X-RateLimit-Remaining</code>, and{' '}
          <code className="text-emerald font-mono">Retry-After</code> headers.
        </div>
      </div>
    </div>
  )
}
export default Plans
