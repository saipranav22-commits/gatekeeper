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
import { Select } from '@/components/ui/Select'
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
  AlertTriangle,
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
    refetch: refetchKeys,
  } = useQuery({
    queryKey: ['consumer-keys', consumerId],
    queryFn: () => apiKeyService.listKeys(consumerId!),
    enabled: !!consumerId,
  })

  // 4. Consumer Analytics Summary Query
  const { data: consumerStats, isLoading: statsLoading } = useQuery({
    queryKey: ['consumer-analytics', consumerId],
    queryFn: () => analyticsService.getConsumerAnalytics(consumerId!),
    enabled: !!consumerId && activeTab === 'analytics',
  })

  // 5. Paginated Usage Events Query
  const { data: usageEvents, isLoading: usageLoading } = useQuery({
    queryKey: ['consumer-usage-events', consumerId, usagePage],
    queryFn: () => analyticsService.getConsumerUsageEvents(consumerId!, undefined, undefined, usagePage, 15),
    enabled: !!consumerId && activeTab === 'analytics',
  })

  // Mutations
  const planMutation = useMutation({
    mutationFn: (newPlanId: string) => consumerService.changePlan(consumerId!, newPlanId),
    onSuccess: (updated) => {
      showToast('success', 'Plan Updated', `Plan changed to ${updated.plan?.name}.`)
      setIsPlanModalOpen(false)
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Update Failed', err.message),
  })

  const suspendMutation = useMutation({
    mutationFn: () => consumerService.suspendConsumer(consumerId!),
    onSuccess: () => {
      showToast('warning', 'Consumer Suspended', 'Consumer traffic will now be rejected.')
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Action Failed', err.message),
  })

  const reactivateMutation = useMutation({
    mutationFn: () => consumerService.reactivateConsumer(consumerId!),
    onSuccess: () => {
      showToast('success', 'Consumer Reactivated', 'Consumer traffic restored.')
      queryClient.invalidateQueries({ queryKey: ['consumer', consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Action Failed', err.message),
  })

  const deleteMutation = useMutation({
    mutationFn: () => consumerService.deleteConsumer(consumerId!),
    onSuccess: () => {
      showToast('success', 'Consumer Soft-Deleted', 'Consumer marked as deleted.')
      navigate('/consumers')
    },
    onError: (err: Error) => showToast('error', 'Deletion Failed', err.message),
  })

  const createKeyMutation = useMutation({
    mutationFn: (payload: { name: string; scopes: string[]; expiresAt?: string }) =>
      apiKeyService.createKey(consumerId!, payload),
    onSuccess: (result) => {
      setIsCreateKeyOpen(false)
      setKeyLabel('')
      setSelectedScopes(['gateway:invoke'])
      setExpiresAtInput('')

      // SECURE ONE-TIME REVEAL:
      // Plaintext key is assigned ONLY to local modal state, never persisted or cached
      setCreatedKeyPlaintext(result.plaintextKey)
      setCreatedKeyName(result.metadata.name)
      setCreatedKeyPrefix(result.metadata.keyPrefix)
      setSecretModalOpen(true)

      refetchKeys()
    },
    onError: (err: Error) => showToast('error', 'Key Generation Failed', err.message),
  })

  const revokeKeyMutation = useMutation({
    mutationFn: ({ keyId, reason }: { keyId: string; reason?: string }) =>
      apiKeyService.revokeKey(consumerId!, keyId, reason),
    onSuccess: (revoked) => {
      showToast('warning', 'API Key Revoked', `Key '${revoked.name}' permanently revoked.`)
      setRevokeTarget(null)
      setRevocationReason('')
      refetchKeys()
    },
    onError: (err: Error) => showToast('error', 'Revocation Failed', err.message),
  })

  const handleCopyConsumerId = () => {
    if (!consumerId) return
    navigator.clipboard?.writeText(consumerId)
    setCopiedId(true)
    setTimeout(() => setCopiedId(false), 2000)
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
      <div className="p-12 text-center bg-white rounded-xl border border-slate-200">
        <ShieldAlert className="w-10 h-10 text-rose-500 mx-auto mb-3" />
        <h2 className="text-lg font-bold text-slate-900">Consumer Not Found</h2>
        <p className="text-xs text-slate-500 mt-1 mb-4">
          The requested consumer does not exist or has already been deleted.
        </p>
        <Button variant="outline" onClick={() => navigate('/consumers')}>
          Return to Consumers List
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Breadcrumb Navigation */}
      <Breadcrumbs items={[{ label: 'Consumers', href: '/consumers' }, { label: consumer.name }]} />

      {/* Header Info Card */}
      <Card>
        <CardContent className="p-6">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6">
            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-bold text-slate-900 tracking-tight">{consumer.name}</h1>
                {consumer.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                {consumer.status === 'SUSPENDED' && <Badge variant="warning">Suspended</Badge>}
                {consumer.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                <span className="font-mono text-slate-700">{consumer.email}</span>
                <span>•</span>
                <span className="flex items-center gap-1 font-mono">
                  ID: {consumer.id.substring(0, 8)}...
                  <button
                    onClick={handleCopyConsumerId}
                    className="p-1 hover:text-slate-800 text-slate-400 rounded transition-colors"
                    title="Copy full UUID"
                  >
                    {copiedId ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
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
                    className="text-xs"
                  >
                    <Layers className="w-3.5 h-3.5 mr-1 text-indigo-600" />
                    Change Plan
                  </Button>

                  {consumer.status === 'ACTIVE' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => suspendMutation.mutate()}
                      isLoading={suspendMutation.isPending}
                      className="text-xs text-amber-700 hover:bg-amber-50"
                    >
                      <PauseCircle className="w-3.5 h-3.5 mr-1" />
                      Suspend
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => reactivateMutation.mutate()}
                      isLoading={reactivateMutation.isPending}
                      className="text-xs text-emerald-700 hover:bg-emerald-50"
                    >
                      <PlayCircle className="w-3.5 h-3.5 mr-1" />
                      Reactivate
                    </Button>
                  )}

                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setIsDeleteConfirmOpen(true)}
                    className="text-xs text-rose-600 hover:bg-rose-50"
                  >
                    <Trash2 className="w-3.5 h-3.5 mr-1" />
                    Soft Delete
                  </Button>
                </>
              )}
            </div>
          </div>

          {/* Current Rate Limit Summary Banner */}
          <div className="mt-6 pt-6 border-t border-slate-100 grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="p-3 bg-slate-50 border border-slate-200/60 rounded-lg">
              <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider">
                Assigned Subscription
              </span>
              <div className="text-base font-bold text-slate-900 mt-0.5">{consumer.plan?.name || 'Standard'}</div>
              <span className="text-xs text-slate-500">{consumer.plan?.description}</span>
            </div>
            <div className="p-3 bg-slate-50 border border-slate-200/60 rounded-lg">
              <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider">Burst Capacity</span>
              <div className="text-base font-bold text-indigo-600 mt-0.5">
                {consumer.plan?.bucketCapacity} tokens
              </div>
              <span className="text-xs text-slate-500">Maximum instantaneous burst quota</span>
            </div>
            <div className="p-3 bg-slate-50 border border-slate-200/60 rounded-lg">
              <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider">Refill Rate</span>
              <div className="text-base font-bold text-emerald-600 mt-0.5">
                {consumer.plan?.refillTokens} tokens / {consumer.plan?.refillPeriodSeconds}s
              </div>
              <span className="text-xs text-slate-500">Atomic Redis token replenishment</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Tabs Navigation */}
      <div className="border-b border-slate-200 flex items-center gap-6">
        <button
          onClick={() => setActiveTab('keys')}
          className={`pb-3 text-sm font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'keys'
              ? 'border-indigo-600 text-indigo-600'
              : 'border-transparent text-slate-500 hover:text-slate-900'
          }`}
        >
          <KeyRound className="w-4 h-4" />
          API Keys ({keys?.length || 0})
        </button>
        <button
          onClick={() => setActiveTab('analytics')}
          className={`pb-3 text-sm font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'analytics'
              ? 'border-indigo-600 text-indigo-600'
              : 'border-transparent text-slate-500 hover:text-slate-900'
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
              <h3 className="text-base font-semibold text-slate-900">Provisioned API Keys</h3>
              <p className="text-xs text-slate-500">
                Credentials authorize requests to <code>/api/v1/gateway/**</code> via <code>X-API-Key</code>
              </p>
            </div>
            {canMutate() && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => setIsCreateKeyOpen(true)}
                className="text-xs shadow-sm bg-indigo-600 hover:bg-indigo-500"
              >
                <Plus className="w-3.5 h-3.5 mr-1" />
                Generate API Key
              </Button>
            )}
          </div>

          <Card>
            <CardContent className="p-0">
              {keysLoading ? (
                <div className="p-6 space-y-3">
                  <Skeleton className="h-8 w-full" />
                  <Skeleton className="h-8 w-full" />
                </div>
              ) : !keys || keys.length === 0 ? (
                <div className="p-12 text-center text-slate-400">
                  <KeyRound className="w-10 h-10 text-slate-300 mx-auto mb-2" />
                  <p className="text-sm font-semibold text-slate-800">No API keys provisioned</p>
                  <p className="text-xs text-slate-500 mt-1">Generate a cryptographic key to enable gateway access.</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm text-slate-700">
                    <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                      <tr>
                        <th className="px-6 py-3.5">Label</th>
                        <th className="px-6 py-3.5">Key Prefix</th>
                        <th className="px-6 py-3.5">Status</th>
                        <th className="px-6 py-3.5">Authorized Scopes</th>
                        <th className="px-6 py-3.5">Last Used</th>
                        <th className="px-6 py-3.5">Expires</th>
                        <th className="px-6 py-3.5 text-right">Action</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 bg-white">
                      {keys.map((k) => (
                        <tr key={k.id} className="hover:bg-slate-50/70 transition-colors">
                          <td className="px-6 py-4 font-semibold text-slate-900">{k.name}</td>
                          <td className="px-6 py-4 font-mono text-xs text-slate-600">{k.keyPrefix}</td>
                          <td className="px-6 py-4">
                            {k.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                            {k.status === 'REVOKED' && <Badge variant="danger">Revoked</Badge>}
                            {k.status === 'EXPIRED' && <Badge variant="warning">Expired</Badge>}
                            {k.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
                          </td>
                          <td className="px-6 py-4">
                            <div className="flex flex-wrap gap-1">
                              {k.scopes.map((s) => (
                                <span
                                  key={s}
                                  className="px-1.5 py-0.5 bg-slate-100 text-slate-700 rounded text-[11px] font-mono border border-slate-200"
                                >
                                  {s}
                                </span>
                              ))}
                            </div>
                          </td>
                          <td className="px-6 py-4 text-xs text-slate-500">
                            {k.lastUsedAt ? (
                              <span className="text-slate-900 font-medium">{formatDate(k.lastUsedAt)}</span>
                            ) : (
                              <span className="text-slate-400 italic">Never used</span>
                            )}
                          </td>
                          <td className="px-6 py-4 text-xs text-slate-500">
                            {k.expiresAt ? formatDate(k.expiresAt) : <span className="text-slate-400">Never</span>}
                          </td>
                          <td className="px-6 py-4 text-right">
                            {canMutate() && k.status === 'ACTIVE' ? (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => setRevokeTarget(k)}
                                className="text-xs text-rose-600 hover:bg-rose-50 border-rose-200"
                              >
                                Revoke
                              </Button>
                            ) : (
                              <span className="text-xs text-slate-400">—</span>
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
            <Card>
              <CardContent className="p-4">
                <span className="text-xs font-semibold text-slate-500 uppercase">Total Requests</span>
                <div className="text-2xl font-bold text-slate-900 mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.totalRequests)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <span className="text-xs font-semibold text-slate-500 uppercase">Allowed (200)</span>
                <div className="text-2xl font-bold text-emerald-600 mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.allowedRequests)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <span className="text-xs font-semibold text-slate-500 uppercase">Rate Limited (429)</span>
                <div className="text-2xl font-bold text-amber-600 mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.rateLimitedRequests)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <span className="text-xs font-semibold text-slate-500 uppercase">Scope Denied (403)</span>
                <div className="text-2xl font-bold text-rose-600 mt-1">
                  {statsLoading ? <Skeleton className="h-7 w-20" /> : formatNumber(consumerStats?.scopeDeniedRequests)}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Paginated Usage Events Audit Table */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Consumer Usage Event Stream</CardTitle>
              <CardDescription>
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
                <div className="p-12 text-center text-slate-400">
                  <Clock className="w-8 h-8 text-slate-300 mx-auto mb-2" />
                  <p className="text-sm font-semibold text-slate-800">No usage events recorded</p>
                  <p className="text-xs text-slate-500 mt-1">Events appear as gateway requests are submitted.</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm text-slate-700">
                    <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                      <tr>
                        <th className="px-6 py-3">Timestamp</th>
                        <th className="px-6 py-3">Method & Path</th>
                        <th className="px-6 py-3">Status</th>
                        <th className="px-6 py-3">Outcome</th>
                        <th className="px-6 py-3">Latency</th>
                        <th className="px-6 py-3">Key Prefix</th>
                        <th className="px-6 py-3">Correlation ID</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 bg-white">
                      {usageEvents.content.map((evt) => (
                        <tr key={evt.id} className="hover:bg-slate-50/70 text-xs">
                          <td className="px-6 py-3.5 text-slate-500 font-mono">{formatDate(evt.timestamp)}</td>
                          <td className="px-6 py-3.5 font-medium text-slate-900">
                            <span className="font-bold text-slate-700 mr-1.5">{evt.httpMethod}</span>
                            <span className="font-mono text-slate-600">{evt.endpointPath}</span>
                          </td>
                          <td className="px-6 py-3.5">
                            <span
                              className={`font-semibold font-mono px-2 py-0.5 rounded text-[11px] ${
                                evt.httpStatus >= 200 && evt.httpStatus < 300
                                  ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                                  : evt.httpStatus === 429
                                  ? 'bg-amber-50 text-amber-700 border border-amber-200'
                                  : 'bg-rose-50 text-rose-700 border border-rose-200'
                              }`}
                            >
                              {evt.httpStatus}
                            </span>
                          </td>
                          <td className="px-6 py-3.5">
                            <span className="font-semibold text-slate-700">{evt.outcomeCategory}</span>
                          </td>
                          <td className="px-6 py-3.5 text-slate-500 font-mono">{evt.latencyMs} ms</td>
                          <td className="px-6 py-3.5 font-mono text-slate-500">
                            {evt.keyPrefixSnapshot || '—'}
                          </td>
                          <td className="px-6 py-3.5 font-mono text-[11px] text-slate-400">
                            {evt.correlationId ? evt.correlationId.substring(0, 8) + '...' : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* Usage Pagination */}
              {usageEvents && usageEvents.totalPages > 1 && (
                <div className="p-4 border-t border-slate-100 flex items-center justify-between text-xs text-slate-500">
                  <div>
                    Page {usageEvents.number + 1} of {usageEvents.totalPages} ({usageEvents.totalElements} events)
                  </div>
                  <div className="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setUsagePage((p) => Math.max(0, p - 1))}
                      disabled={usageEvents.first}
                      className="text-xs py-1 px-2.5"
                    >
                      <ChevronLeft className="w-3.5 h-3.5 mr-1" />
                      Prev
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setUsagePage((p) => p + 1)}
                      disabled={usageEvents.last}
                      className="text-xs py-1 px-2.5"
                    >
                      Next
                      <ChevronRight className="w-3.5 h-3.5 ml-1" />
                    </Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
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

          <div className="space-y-1.5 text-left">
            <label className="text-xs font-semibold uppercase tracking-wider text-slate-700">
              Authorized Scopes
            </label>
            <div className="space-y-2 border border-slate-200 rounded-lg p-3 bg-slate-50">
              {AVAILABLE_SCOPES.map((sc) => (
                <label key={sc.id} className="flex items-start gap-2.5 text-xs text-slate-800 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={selectedScopes.includes(sc.id)}
                    onChange={() => toggleScope(sc.id)}
                    className="mt-0.5 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                  />
                  <div>
                    <span className="font-mono font-semibold">{sc.label}</span>
                    <p className="text-[11px] text-slate-500">{sc.desc}</p>
                  </div>
                </label>
              ))}
            </div>
          </div>

          <Input
            label="Custom Scope (Optional)"
            placeholder="e.g. custom:permission"
            value={customScope}
            onChange={(e) => setCustomScope(e.target.value)}
            helperText="Add an application-specific permission scope"
          />

          <Input
            label="Expiration Date (Optional UTC)"
            type="datetime-local"
            value={expiresAtInput}
            onChange={(e) => setExpiresAtInput(e.target.value)}
            helperText="Leave blank for a non-expiring key"
          />

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" type="button" onClick={() => setIsCreateKeyOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" isLoading={createKeyMutation.isPending}>
              Generate Key
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
      <Modal
        isOpen={!!revokeTarget}
        onClose={() => setRevokeTarget(null)}
        title="Revoke API Key"
        description="Permanently revoke cryptographic credential"
      >
        <div className="space-y-4">
          <div className="p-3.5 bg-rose-50 border border-rose-200 rounded-lg text-rose-900 text-xs flex items-start gap-2.5">
            <AlertTriangle className="w-5 h-5 text-rose-600 shrink-0 mt-0.5" />
            <div className="leading-relaxed">
              Are you sure you want to revoke key <strong>{revokeTarget?.name}</strong> (prefix:{' '}
              <code>{revokeTarget?.keyPrefix}</code>)? Any client sending this key will immediately receive HTTP 401
              Unauthorized. This action is irreversible.
            </div>
          </div>

          <Input
            label="Revocation Reason (Optional)"
            placeholder="e.g. Secret rotation or compromised environment"
            value={revocationReason}
            onChange={(e) => setRevocationReason(e.target.value)}
          />

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRevokeTarget(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() =>
                revokeTarget &&
                revokeKeyMutation.mutate({ keyId: revokeTarget.id, reason: revocationReason })
              }
              isLoading={revokeKeyMutation.isPending}
            >
              Confirm Revocation
            </Button>
          </div>
        </div>
      </Modal>

      {/* Modal: Change Subscription Plan */}
      <Modal
        isOpen={isPlanModalOpen}
        onClose={() => setIsPlanModalOpen(false)}
        title="Migrate Subscription Plan"
        description={`Update token bucket rate limits for ${consumer.name}`}
      >
        <div className="space-y-4">
          <Select
            label="Target Plan"
            value={targetPlanId}
            onChange={(e) => setTargetPlanId(e.target.value)}
            required
          >
            {plans?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} — {p.bucketCapacity} tokens ({p.refillTokens}/{p.refillPeriodSeconds}s)
              </option>
            ))}
          </Select>

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setIsPlanModalOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={() => planMutation.mutate(targetPlanId)}
              isLoading={planMutation.isPending}
            >
              Confirm Plan Update
            </Button>
          </div>
        </div>
      </Modal>

      {/* Modal: Soft Delete Confirmation */}
      <Modal
        isOpen={isDeleteConfirmOpen}
        onClose={() => setIsDeleteConfirmOpen(false)}
        title="Soft Delete Consumer"
        description="Permanently revoke access for this consumer"
      >
        <div className="space-y-4">
          <div className="p-3.5 bg-rose-50 border border-rose-200 rounded-lg text-rose-900 text-xs">
            Soft-deleting <strong>{consumer.name}</strong> will deactivate all associated keys immediately. Historical
            usage analytics and request logs will remain preserved.
          </div>
          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setIsDeleteConfirmOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() => deleteMutation.mutate()}
              isLoading={deleteMutation.isPending}
            >
              Confirm Deletion
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
