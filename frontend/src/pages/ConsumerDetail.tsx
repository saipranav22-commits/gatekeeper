import React, { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { consumerService } from '@/services/consumerService'
import { apiKeyService } from '@/services/apiKeyService'
import { planService } from '@/services/planService'
import { analyticsService } from '@/services/analyticsService'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import { Skeleton } from '@/components/ui/Skeleton'
import { Breadcrumbs } from '@/components/ui/Breadcrumbs'
import { OneTimeSecretModal } from '@/components/auth/OneTimeSecretModal'
import { formatDate, formatNumber } from '@/lib/utils'
import { ApiKeyMetadataDto } from '@/types/apiKey'
import {
  KeyRound,
  BarChart3,
  Plus,
  Copy,
  Check,
  PauseCircle,
  PlayCircle,
  Trash2,
  ShieldAlert,
  Clock,
  Layers,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'

const AVAILABLE_SCOPES = [
  { id: 'gateway:invoke', label: 'gateway:invoke', desc: 'Standard gateway endpoint execution' },
  { id: 'read:orders', label: 'read:orders', desc: 'Read consumer orders and telemetry' },
  { id: 'write:orders', label: 'write:orders', desc: 'Create and update orders' },
  { id: 'analytics:read', label: 'analytics:read', desc: 'Access consumer-level usage metrics' },
]

export const ConsumerDetail: React.FC = () => {
  const { consumerId } = useParams<{ consumerId: string }>()
  const navigate = useNavigate()
  const { canMutate } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  const [activeTab, setActiveTab] = useState<'keys' | 'analytics'>('keys')
  const [copiedId, setCopiedId] = useState<boolean>(false)

  // Plan change modal
  const [isPlanModalOpen, setIsPlanModalOpen] = useState<boolean>(false)
  const [targetPlanId, setTargetPlanId] = useState<string>('')

  // Soft delete confirm
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState<boolean>(false)

  // API Key creation
  const [isCreateKeyOpen, setIsCreateKeyOpen] = useState<boolean>(false)
  const [keyLabel, setKeyLabel] = useState<string>('')
  const [selectedScopes, setSelectedScopes] = useState<string[]>(['gateway:invoke'])
  const [customScope, setCustomScope] = useState<string>('')
  const [expiresAtInput, setExpiresAtInput] = useState<string>('')

  // ONE-TIME SECRET REVEAL STATE (CRITICAL SECRET HANDLING)
  const [secretModalOpen, setSecretModalOpen] = useState<boolean>(false)
  const [createdKeyPlaintext, setCreatedKeyPlaintext] = useState<string | null>(null)
  const [createdKeyName, setCreatedKeyName] = useState<string>('')
  const [createdKeyPrefix, setCreatedKeyPrefix] = useState<string>('')

  // Revoke Key confirm
  const [revokeTarget, setRevokeTarget] = useState<ApiKeyMetadataDto | null>(null)
  const [revocationReason, setRevocationReason] = useState<string>('')

  // Audit Events Pagination
  const [usagePage, setUsagePage] = useState<number>(0)

  // 1. Consumer Query
  const {
    data: consumer,
    isLoading: consumerLoading,
    error: consumerError,
  } = useQuery({
    queryKey: ['consumer', consumerId],
    queryFn: () => consumerService.getConsumer(consumerId!),
    enabled: !!consumerId,
  })

  // 2. Plans Query
  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => planService.listPlans(),
  })

  // 3. API Keys Query
  const {
    data: keys,
    isLoading: keysLoading,
    error: keysError,
  } = useQuery({
    queryKey: ['consumer-keys', consumerId],
    queryFn: () => apiKeyService.listKeys(consumerId!),
    enabled: !!consumerId,
  })

  // 4. Consumer Analytics Summary Query
  const { data: consumerStats, isLoading: statsLoading } = useQuery({
    queryKey: ['consumer-stats', consumerId],
    queryFn: () => analyticsService.getConsumerAnalytics(consumerId!),
    enabled: !!consumerId && activeTab === 'analytics',
  })

  // 5. Paginated Usage Audit Events Query
  const { data: usageEvents, isLoading: usageLoading } = useQuery({
    queryKey: ['consumer-usage-events', consumerId, usagePage],
    queryFn: () => analyticsService.getConsumerUsageEvents(consumerId!, undefined, undefined, usagePage, 15),
    enabled: !!consumerId && activeTab === 'analytics',
  })

  // Mutations
  const changePlanMutation = useMutation({
    mutationFn: (newPlanId: string) => consumerService.changePlan(consumerId!, newPlanId),
    onSuccess: (updated) => {
      showToast('success', 'Plan Updated', `Consumer migrated to plan '${updated.plan?.name}'.`)
      setIsPlanModalOpen(false)
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Plan Migration Failed', err.message),
  })

  const suspendMutation = useMutation({
    mutationFn: () => consumerService.suspendConsumer(consumerId!),
    onSuccess: () => {
      showToast('warning', 'Consumer Suspended', 'Access suspended. Ingress requests will be halted.')
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Suspend Failed', err.message),
  })

  const reactivateMutation = useMutation({
    mutationFn: () => consumerService.reactivateConsumer(consumerId!),
    onSuccess: () => {
      showToast('success', 'Consumer Reactivated', 'Consumer access restored.')
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Reactivation Failed', err.message),
  })

  const deleteMutation = useMutation({
    mutationFn: () => consumerService.deleteConsumer(consumerId!),
    onSuccess: () => {
      showToast('success', 'Consumer Soft-Deleted', 'Consumer deleted. Redirecting to consumer directory...')
      setIsDeleteConfirmOpen(false)
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
      navigate('/consumers')
    },
    onError: (err: Error) => showToast('error', 'Delete Failed', err.message),
  })

  const createKeyMutation = useMutation({
    mutationFn: (payload: { name: string; scopes: string[]; expiresAt?: string }) =>
      apiKeyService.createKey(consumerId!, payload),
    onSuccess: (result) => {
      setIsCreateKeyOpen(false)
      setKeyLabel('')
      setSelectedScopes(['gateway:invoke'])
      setCustomScope('')
      setExpiresAtInput('')

      // SECURE ONE-TIME REVEAL
      setCreatedKeyPlaintext(result.plaintextKey)
      setCreatedKeyName(result.metadata.name)
      setCreatedKeyPrefix(result.metadata.keyPrefix)
      setSecretModalOpen(true)

      queryClient.invalidateQueries({ queryKey: ['consumer-keys', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Key Creation Failed', err.message),
  })

  const revokeKeyMutation = useMutation({
    mutationFn: ({ keyId, reason }: { keyId: string; reason?: string }) =>
      apiKeyService.revokeKey(consumerId!, keyId, reason),
    onSuccess: (revoked) => {
      showToast('warning', 'Key Revoked', `Key '${revoked.name}' permanently invalidated.`)
      setRevokeTarget(null)
      setRevocationReason('')
      queryClient.invalidateQueries({ queryKey: ['consumer-keys', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Revocation Failed', err.message),
  })

  const handleCopyConsumerId = () => {
    if (consumer?.id) {
      navigator.clipboard.writeText(consumer.id)
      setCopiedId(true)
      setTimeout(() => setCopiedId(false), 2000)
    }
  }

  const handleChangePlanSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!targetPlanId) return
    changePlanMutation.mutate(targetPlanId)
  }

  const handleCreateKeySubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!keyLabel.trim()) {
      showToast('warning', 'Validation Error', 'Key label is required.')
      return
    }
    const finalScopes = [...selectedScopes]
    if (customScope.trim() && !finalScopes.includes(customScope.trim())) {
      finalScopes.push(customScope.trim())
    }
    if (finalScopes.length === 0) {
      showToast('warning', 'Validation Error', 'At least one scope must be selected.')
      return
    }

    createKeyMutation.mutate({
      name: keyLabel.trim(),
      scopes: finalScopes,
      expiresAt: expiresAtInput ? new Date(expiresAtInput).toISOString() : undefined,
    })
  }

  const toggleScope = (scopeId: string) => {
    setSelectedScopes((prev) =>
      prev.includes(scopeId) ? prev.filter((s) => s !== scopeId) : [...prev, scopeId]
    )
  }

  const handleCloseSecretModal = () => {
    // SECURE WIPE: Clear plaintext credential from memory
    setCreatedKeyPlaintext(null)
    setSecretModalOpen(false)
  }

  if (consumerLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (consumerError || !consumer) {
    return (
      <Card className="p-12 text-center bg-surface border-crimson/30">
        <ShieldAlert className="w-10 h-10 text-crimson mx-auto mb-3" />
        <h2 className="text-lg font-bold text-silver-frost">Consumer Not Found</h2>
        <p className="text-xs text-titanium mt-1 mb-4">
          The requested consumer does not exist or has already been deleted.
        </p>
        <Button variant="outline" onClick={() => navigate('/consumers')}>
          Return to Consumers List
        </Button>
      </Card>
    )
  }

  return (
    <div className="space-y-6">
      {/* Breadcrumb Navigation */}
      <Breadcrumbs items={[{ label: 'Consumers', href: '/consumers' }, { label: consumer.name }]} />

      {/* Header Info Card */}
      <Card className="bg-surface border-moss/40">
        <CardContent className="p-6">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6">
            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-bold text-silver-frost tracking-tight">{consumer.name}</h1>
                {consumer.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                {consumer.status === 'SUSPENDED' && <Badge variant="warning">Suspended</Badge>}
                {consumer.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-titanium">
                <span className="font-mono text-silver-frost">{consumer.email}</span>
                <span>•</span>
                <span className="flex items-center gap-1 font-mono">
                  ID: {consumer.id.substring(0, 8)}...
                  <button
                    onClick={handleCopyConsumerId}
                    className="p-1 hover:text-silver-frost text-titanium-muted rounded transition-colors"
                    title="Copy full UUID"
                  >
                    {copiedId ? <Check className="w-3.5 h-3.5 text-emerald" /> : <Copy className="w-3.5 h-3.5" />}
                  </button>
                </span>
                <span>•</span>
                <span>Registered: {formatDate(consumer.createdAt)}</span>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="flex flex-wrap items-center gap-2">
              {canMutate() && (
                <>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setTargetPlanId(consumer.plan?.id || (plans ? plans[0]?.id : ''))
                      setIsPlanModalOpen(true)
                    }}
                    className="text-xs bg-surface border-moss/40 text-titanium hover:text-silver-frost"
                  >
                    <Layers className="w-3.5 h-3.5 mr-1 text-emerald" />
                    Change Plan
                  </Button>

                  {consumer.status === 'ACTIVE' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => suspendMutation.mutate()}
                      disabled={suspendMutation.isPending}
                      className="text-xs text-amber hover:bg-amber/10 border-amber/30"
                    >
                      <PauseCircle className="w-3.5 h-3.5 mr-1" />
                      Suspend
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => reactivateMutation.mutate()}
                      disabled={reactivateMutation.isPending}
                      className="text-xs text-emerald hover:bg-emerald/10 border-emerald/30"
                    >
                      <PlayCircle className="w-3.5 h-3.5 mr-1" />
                      Reactivate
                    </Button>
                  )}

                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setIsDeleteConfirmOpen(true)}
                    className="text-xs text-crimson hover:bg-crimson/10 border-crimson/30"
                  >
                    <Trash2 className="w-3.5 h-3.5 mr-1" />
                    Soft Delete
                  </Button>
                </>
              )}
            </div>
          </div>

          {/* Current Rate Limit Summary Banner */}
          <div className="mt-6 pt-6 border-t border-moss/30 grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="p-3 bg-surface-elevated border border-moss/40 rounded-lg">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">
                Assigned Subscription
              </span>
              <div className="text-base font-bold text-silver-frost mt-0.5">{consumer.plan?.name || 'Standard'}</div>
              <span className="text-xs text-titanium-muted">{consumer.plan?.description}</span>
            </div>
            <div className="p-3 bg-surface-elevated border border-moss/40 rounded-lg">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Burst Capacity</span>
              <div className="text-base font-bold font-mono text-emerald mt-0.5">
                {consumer.plan?.bucketCapacity} tokens
              </div>
              <span className="text-xs text-titanium-muted">Maximum instantaneous burst quota</span>
            </div>
            <div className="p-3 bg-surface-elevated border border-moss/40 rounded-lg">
              <span className="text-[11px] font-semibold text-titanium uppercase tracking-wider">Refill Rate</span>
              <div className="text-base font-bold font-mono text-silver-frost mt-0.5">
                {consumer.plan?.refillTokens} tokens / {consumer.plan?.refillPeriodSeconds}s
              </div>
              <span className="text-xs text-titanium-muted">Atomic Redis token replenishment</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Tabs Navigation */}
      <div className="border-b border-moss/40 flex items-center gap-6">
        <button
          onClick={() => setActiveTab('keys')}
          className={`pb-3 text-sm font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'keys'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <KeyRound className="w-4 h-4" />
          API Keys ({keys?.length || 0})
        </button>
        <button
          onClick={() => setActiveTab('analytics')}
          className={`pb-3 text-sm font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'analytics'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <BarChart3 className="w-4 h-4" />
          Telemetry & Usage Events
        </button>
      </div>

      {/* TAB 1: API Keys */}
      {activeTab === 'keys' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-silver-frost">Provisioned API Keys</h3>
              <p className="text-xs text-titanium">
                Credentials authorize requests to <code>/api/v1/gateway/**</code> via <code>X-API-Key</code>
              </p>
            </div>
            {canMutate() && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => setIsCreateKeyOpen(true)}
                className="text-xs"
              >
                <Plus className="w-3.5 h-3.5 mr-1" />
                Generate API Key
              </Button>
            )}
          </div>

          <Card className="bg-surface border border-moss/40 overflow-hidden">
            <CardContent className="p-0">
              {keysLoading ? (
                <div className="p-6 space-y-3">
                  <Skeleton className="h-8 w-full" />
                  <Skeleton className="h-8 w-full" />
                </div>
              ) : keysError ? (
                <div className="p-12 text-center text-titanium-muted">
                  <ShieldAlert className="w-8 h-8 text-crimson mx-auto mb-2" />
                  <p className="text-sm font-semibold text-silver-frost">Failed to load API keys</p>
                </div>
              ) : !keys || keys.length === 0 ? (
                <div className="p-12 text-center text-titanium-muted">
                  <KeyRound className="w-10 h-10 text-titanium-muted/40 mx-auto mb-2" />
                  <p className="text-sm font-semibold text-silver-frost">No API keys provisioned</p>
                  <p className="text-xs text-titanium mt-1">Generate a cryptographic key to enable gateway access.</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs border-collapse">
                    <thead className="bg-surface-elevated/70 border-b border-moss/30 text-titanium uppercase tracking-wider font-medium text-[11px]">
                      <tr>
                        <th className="px-5 py-3">Label</th>
                        <th className="px-5 py-3">Key Prefix</th>
                        <th className="px-5 py-3">Status</th>
                        <th className="px-5 py-3">Authorized Scopes</th>
                        <th className="px-5 py-3">Created</th>
                        <th className="px-5 py-3">Expires</th>
                        <th className="px-5 py-3 text-right">Action</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-moss/20 text-silver-frost">
                      {keys.map((k) => (
                        <tr key={k.id} className="hover:bg-surface-elevated/40 transition-colors">
                          <td className="px-5 py-3.5 font-semibold text-silver-frost">{k.name}</td>
                          <td className="px-5 py-3.5 font-mono text-[11px] text-emerald">{k.keyPrefix}</td>
                          <td className="px-5 py-3.5">
                            {k.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                            {k.status === 'REVOKED' && <Badge variant="danger">Revoked</Badge>}
                            {k.status === 'EXPIRED' && <Badge variant="warning">Expired</Badge>}
                            {k.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
                          </td>
                          <td className="px-5 py-3.5">
                            <div className="flex flex-wrap gap-1">
                              {k.scopes.map((s) => (
                                <span
                                  key={s}
                                  className="px-1.5 py-0.5 bg-surface-elevated text-silver-frost rounded text-[10px] font-mono border border-moss/30"
                                >
                                  {s}
                                </span>
                              ))}
                            </div>
                          </td>
                          <td className="px-5 py-3.5 text-titanium font-mono text-[11px]">{formatDate(k.createdAt)}</td>
                          <td className="px-5 py-3.5 text-titanium font-mono text-[11px]">
                            {k.expiresAt ? formatDate(k.expiresAt) : 'Never'}
                          </td>
                          <td className="px-5 py-3.5 text-right">
                            {canMutate() && k.status === 'ACTIVE' ? (
                              <Button
                                variant="danger"
                                size="sm"
                                onClick={() => setRevokeTarget(k)}
                                className="text-[11px] py-1 px-2"
                              >
                                Revoke
                              </Button>
                            ) : k.revocationReason ? (
                              <span className="text-[11px] text-titanium-muted italic">{k.revocationReason}</span>
                            ) : (
                              <span className="text-[11px] text-titanium-muted">—</span>
                            )}
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
      )}

      {/* TAB 2: Telemetry & Usage Events */}
      {activeTab === 'analytics' && (
        <div className="space-y-6">
          {/* Consumer KPI summary */}
          <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
            <Card className="bg-surface border-moss/40">
              <CardContent className="p-4">
                <span className="text-[11px] font-semibold text-titanium uppercase">Total Requests</span>
                <div className="text-xl font-bold font-mono text-silver-frost mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.totalRequests)}
                </div>
              </CardContent>
            </Card>
            <Card className="bg-surface border-moss/40">
              <CardContent className="p-4">
                <span className="text-[11px] font-semibold text-titanium uppercase">Allowed (200)</span>
                <div className="text-xl font-bold font-mono text-emerald mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.allowedRequests)}
                </div>
              </CardContent>
            </Card>
            <Card className="bg-surface border-moss/40">
              <CardContent className="p-4">
                <span className="text-[11px] font-semibold text-titanium uppercase">Rate Limited (429)</span>
                <div className="text-xl font-bold font-mono text-amber mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.rateLimitedRequests)}
                </div>
              </CardContent>
            </Card>
            <Card className="bg-surface border-moss/40">
              <CardContent className="p-4">
                <span className="text-[11px] font-semibold text-titanium uppercase">Scope Denied (403)</span>
                <div className="text-xl font-bold font-mono text-crimson mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.scopeDeniedRequests)}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Paginated Usage Events Audit Table */}
          <Card className="bg-surface border-moss/40 overflow-hidden">
            <CardHeader className="border-b border-moss/30 bg-surface-elevated/40 pb-3">
              <CardTitle className="text-sm font-semibold text-silver-frost">Consumer Usage Event Stream</CardTitle>
              <CardDescription className="text-xs text-titanium">
                Audited individual gateway invocations batched from Redis Streams
              </CardDescription>
            </CardHeader>
            <CardContent className="p-0">
              {usageLoading ? (
                <div className="p-6 space-y-3">
                  <Skeleton className="h-8 w-full" />
                  <Skeleton className="h-8 w-full" />
                </div>
              ) : !usageEvents || usageEvents.content.length === 0 ? (
                <div className="p-12 text-center text-titanium-muted">
                  <Clock className="w-8 h-8 text-titanium-muted/40 mx-auto mb-2" />
                  <p className="text-sm font-semibold text-silver-frost">No usage events recorded</p>
                  <p className="text-xs text-titanium mt-1">Events appear as gateway requests are submitted.</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs border-collapse">
                    <thead className="bg-surface-elevated/70 border-b border-moss/30 text-titanium uppercase tracking-wider font-medium text-[11px]">
                      <tr>
                        <th className="px-5 py-3">Timestamp</th>
                        <th className="px-5 py-3">Method & Path</th>
                        <th className="px-5 py-3">Status</th>
                        <th className="px-5 py-3">Outcome</th>
                        <th className="px-5 py-3">Latency</th>
                        <th className="px-5 py-3">Key Prefix</th>
                        <th className="px-5 py-3">Correlation ID</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-moss/20 text-silver-frost">
                      {usageEvents.content.map((evt) => (
                        <tr key={evt.id} className="hover:bg-surface-elevated/40">
                          <td className="px-5 py-3 text-titanium font-mono text-[11px]">{formatDate(evt.timestamp)}</td>
                          <td className="px-5 py-3 font-medium">
                            <span className="font-bold text-silver-frost mr-1.5">{evt.httpMethod}</span>
                            <span className="font-mono text-titanium">{evt.endpointPath}</span>
                          </td>
                          <td className="px-5 py-3">
                            <span
                              className={`font-semibold font-mono px-2 py-0.5 rounded text-[10px] ${
                                evt.httpStatus >= 200 && evt.httpStatus < 300
                                  ? 'bg-emerald/15 text-emerald border border-emerald/30'
                                  : evt.httpStatus === 429
                                  ? 'bg-amber/15 text-amber border border-amber/30'
                                  : 'bg-crimson/15 text-crimson border border-crimson/30'
                              }`}
                            >
                              {evt.httpStatus}
                            </span>
                          </td>
                          <td className="px-5 py-3">
                            <span className="font-medium text-silver-frost">{evt.outcomeCategory}</span>
                          </td>
                          <td className="px-5 py-3 text-titanium font-mono">{evt.latencyMs} ms</td>
                          <td className="px-5 py-3 font-mono text-titanium">
                            {evt.keyPrefixSnapshot || '—'}
                          </td>
                          <td className="px-5 py-3 font-mono text-[10px] text-titanium-muted">
                            {evt.correlationId ? evt.correlationId.substring(0, 10) + '...' : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* Usage Pagination */}
              {usageEvents && usageEvents.totalPages > 1 && (
                <div className="p-3 border-t border-moss/30 bg-surface-elevated/40 flex items-center justify-between text-xs text-titanium">
                  <div>
                    Page <span className="font-mono text-silver-frost">{usageEvents.number + 1}</span> of{' '}
                    <span className="font-mono text-silver-frost">{usageEvents.totalPages}</span> (
                    <span className="font-mono text-silver-frost">{usageEvents.totalElements}</span> events)
                  </div>
                  <div className="flex items-center gap-1.5">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setUsagePage((p) => Math.max(0, p - 1))}
                      disabled={usageEvents.first}
                      className="p-1.5 bg-surface border-moss/40"
                    >
                      <ChevronLeft className="w-4 h-4" />
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setUsagePage((p) => p + 1)}
                      disabled={usageEvents.last}
                      className="p-1.5 bg-surface border-moss/40"
                    >
                      <ChevronRight className="w-4 h-4" />
                    </Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* Modal: Change Subscription Plan */}
      {isPlanModalOpen && (
        <Modal
          isOpen={isPlanModalOpen}
          onClose={() => setIsPlanModalOpen(false)}
          title={`Migrate Plan: ${consumer.name}`}
          description="Adjust the assigned rate-limiting bucket. Changes propagate immediately to Gateway Redis clusters."
        >
          <form onSubmit={handleChangePlanSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-titanium mb-1">Select Target Plan</label>
              <select
                value={targetPlanId}
                onChange={(e) => setTargetPlanId(e.target.value)}
                className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
                required
              >
                {plans?.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.bucketCapacity} burst tokens, {p.refillTokens} / {p.refillPeriodSeconds}s)
                  </option>
                ))}
              </select>
            </div>

            <div className="p-3 bg-surface-elevated border border-amber/30 rounded-lg text-xs text-titanium">
              <span className="font-semibold text-amber">Operational Impact:</span> Active consumer API keys will
              immediately inherit the new burst capacity and token refill velocity.
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" type="button" onClick={() => setIsPlanModalOpen(false)}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" disabled={changePlanMutation.isPending}>
                {changePlanMutation.isPending ? 'Updating...' : 'Migrate Plan'}
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Modal: Soft Delete Confirmation */}
      {isDeleteConfirmOpen && (
        <Modal
          isOpen={isDeleteConfirmOpen}
          onClose={() => setIsDeleteConfirmOpen(false)}
          title="Confirm Consumer Soft-Deletion"
          description="Mark this consumer as deleted. All active API keys will be immediately halted."
        >
          <div className="space-y-4">
            <div className="p-3 bg-crimson/10 border border-crimson/30 rounded-lg text-xs text-crimson leading-relaxed">
              <strong>Warning:</strong> Consumer <strong>{consumer.name}</strong> ({consumer.email}) and its associated
              keys will be deactivated. Audit telemetry records in PostgreSQL and Redis will be preserved.
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" onClick={() => setIsDeleteConfirmOpen(false)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? 'Deleting...' : 'Soft-Delete Consumer'}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Modal: Generate API Key */}
      <Modal
        isOpen={isCreateKeyOpen}
        onClose={() => setIsCreateKeyOpen(false)}
        title="Generate Consumer API Key"
        description="Generates an authentication key. Plaintext secret is revealed exactly once."
      >
        <form onSubmit={handleCreateKeySubmit} className="space-y-4">
          <Input
            label="API Key Label / Purpose"
            placeholder="e.g. Production Ingestion Service"
            value={keyLabel}
            onChange={(e) => setKeyLabel(e.target.value)}
            required
          />

          <div>
            <label className="block text-xs font-semibold text-titanium mb-2">Authorized Scopes</label>
            <div className="space-y-2">
              {AVAILABLE_SCOPES.map((sc) => {
                const isChecked = selectedScopes.includes(sc.id)
                return (
                  <label
                    key={sc.id}
                    className={`flex items-start gap-2.5 p-2 rounded-lg border cursor-pointer transition-colors ${
                      isChecked
                        ? 'border-emerald/40 bg-emerald/5 text-silver-frost'
                        : 'border-moss/30 hover:border-moss text-titanium'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={isChecked}
                      onChange={() => toggleScope(sc.id)}
                      className="mt-0.5 rounded border-moss text-emerald focus:ring-emerald"
                    />
                    <div>
                      <div className="font-mono text-xs font-medium">{sc.label}</div>
                      <div className="text-[11px] text-titanium-muted">{sc.desc}</div>
                    </div>
                  </label>
                )
              })}
            </div>
          </div>

          <Input
            label="Custom Scope (Optional)"
            placeholder="e.g. custom:permission"
            value={customScope}
            onChange={(e) => setCustomScope(e.target.value)}
          />

          <Input
            label="Expiration Date (Optional UTC)"
            type="datetime-local"
            value={expiresAtInput}
            onChange={(e) => setExpiresAtInput(e.target.value)}
          />

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" type="button" onClick={() => setIsCreateKeyOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={createKeyMutation.isPending}>
              {createKeyMutation.isPending ? 'Generating...' : 'Generate Key'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* SECURE ONE-TIME REVEAL MODAL */}
      <OneTimeSecretModal
        isOpen={secretModalOpen}
        onClose={handleCloseSecretModal}
        keyName={createdKeyName}
        keyPrefix={createdKeyPrefix}
        plaintextKey={createdKeyPlaintext}
      />

      {/* Modal: Revoke Key Confirmation */}
      {revokeTarget && (
        <Modal
          isOpen={!!revokeTarget}
          onClose={() => setRevokeTarget(null)}
          title="Revoke API Key"
          description="Permanently revoke cryptographic credential"
        >
          <div className="space-y-4">
            <div className="p-3 bg-crimson/10 border border-crimson/30 rounded-lg text-xs text-crimson leading-relaxed">
              <strong>Warning:</strong> Key <strong>{revokeTarget.name}</strong> (
              <span className="font-mono">{revokeTarget.keyPrefix}</span>) will be permanently revoked. Ingress traffic
              authenticated with this key will be rejected.
            </div>

            <div>
              <label className="block text-xs font-semibold text-titanium mb-1">Revocation Audit Reason</label>
              <textarea
                value={revocationReason}
                onChange={(e) => setRevocationReason(e.target.value)}
                placeholder="Reason for key invalidation..."
                rows={3}
                maxLength={255}
                className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-crimson"
              />
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" onClick={() => setRevokeTarget(null)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                onClick={() =>
                  revokeKeyMutation.mutate({
                    keyId: revokeTarget.id,
                    reason: revocationReason || undefined,
                  })
                }
                disabled={revokeKeyMutation.isPending}
              >
                {revokeKeyMutation.isPending ? 'Revoking...' : 'Revoke API Key'}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
export default ConsumerDetail
