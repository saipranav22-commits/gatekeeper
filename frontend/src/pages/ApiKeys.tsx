import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { consumerService } from '@/services/consumerService'
import { apiKeyService } from '@/services/apiKeyService'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import { Skeleton } from '@/components/ui/Skeleton'
import { OneTimeSecretModal } from '@/components/auth/OneTimeSecretModal'
import { formatDate } from '@/lib/utils'
import { ApiKeyMetadataDto } from '@/types/apiKey'
import {
  KeyRound,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  Trash2,
} from 'lucide-react'

const AVAILABLE_SCOPES = [
  { id: 'gateway:invoke', label: 'gateway:invoke', desc: 'Standard gateway endpoint execution' },
  { id: 'read:orders', label: 'read:orders', desc: 'Read consumer orders and telemetry' },
  { id: 'write:orders', label: 'write:orders', desc: 'Create and update orders' },
  { id: 'analytics:read', label: 'analytics:read', desc: 'Access consumer-level usage metrics' },
]

export const ApiKeys: React.FC = () => {
  const { canMutate } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  const [selectedConsumerId, setSelectedConsumerId] = useState<string>('')
  const [searchQuery, setSearchQuery] = useState<string>('')
  const [searchParams, setSearchParams] = useSearchParams()

  // Create Key Modal
  const [isCreateModalOpen, setIsCreateModalOpen] = useState<boolean>(false)
  const isCreateOpen = isCreateModalOpen || (searchParams.get('action') === 'create' && canMutate())
  const [createConsumerId, setCreateConsumerId] = useState<string>('')
  const [keyLabel, setKeyLabel] = useState<string>('')
  const [selectedScopes, setSelectedScopes] = useState<string[]>(['gateway:invoke'])
  const [expiresAtInput, setExpiresAtInput] = useState<string>('')

  const handleCloseCreate = () => {
    setIsCreateModalOpen(false)
    if (searchParams.get('action') === 'create') {
      const newParams = new URLSearchParams(searchParams)
      newParams.delete('action')
      setSearchParams(newParams, { replace: true })
    }
  }

  // ONE-TIME SECRET MODAL STATE
  const [secretModalOpen, setSecretModalOpen] = useState<boolean>(false)
  const [createdKeyPlaintext, setCreatedKeyPlaintext] = useState<string | null>(null)
  const [createdKeyName, setCreatedKeyName] = useState<string>('')
  const [createdKeyPrefix, setCreatedKeyPrefix] = useState<string>('')

  // Revoke Key Modal
  const [revokeTarget, setRevokeTarget] = useState<{ consumerId: string; key: ApiKeyMetadataDto } | null>(null)
  const [revocationReason, setRevocationReason] = useState<string>('')

  // 1. Fetch Consumers
  const { data: consumersPage, isLoading: consumersLoading } = useQuery({
    queryKey: ['consumers', 0, 100],
    queryFn: () => consumerService.listConsumers(0, 100, 'name', 'asc'),
  })

  const consumers = React.useMemo(() => consumersPage?.content || [], [consumersPage])
  const activeConsumerId = selectedConsumerId || (consumers.length > 0 ? consumers[0].id : '')

  // 2. Fetch Keys for selected consumer
  const {
    data: keys,
    isLoading: keysLoading,
    isFetching,
    refetch,
    error: keysError,
  } = useQuery({
    queryKey: ['consumer-keys', activeConsumerId],
    queryFn: () => apiKeyService.listKeys(activeConsumerId),
    enabled: !!activeConsumerId,
  })

  // Mutations
  const createKeyMutation = useMutation({
    mutationFn: (payload: { consumerId: string; name: string; scopes: string[]; expiresAt?: string }) =>
      apiKeyService.createKey(payload.consumerId, {
        name: payload.name,
        scopes: payload.scopes,
        expiresAt: payload.expiresAt,
      }),
    onSuccess: (result, variables) => {
      handleCloseCreate()
      setKeyLabel('')
      setSelectedScopes(['gateway:invoke'])
      setExpiresAtInput('')

      // SECURE ONE-TIME REVEAL
      setCreatedKeyPlaintext(result.plaintextKey)
      setCreatedKeyName(result.metadata.name)
      setCreatedKeyPrefix(result.metadata.keyPrefix)
      setSecretModalOpen(true)

      queryClient.invalidateQueries({ queryKey: ['consumer-keys', variables.consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Key Generation Failed', err.message),
  })

  const revokeKeyMutation = useMutation({
    mutationFn: ({ consumerId, keyId, reason }: { consumerId: string; keyId: string; reason?: string }) =>
      apiKeyService.revokeKey(consumerId, keyId, reason),
    onSuccess: (revoked, variables) => {
      showToast('warning', 'API Key Revoked', `Key '${revoked.name}' permanently revoked.`)
      setRevokeTarget(null)
      setRevocationReason('')
      queryClient.invalidateQueries({ queryKey: ['consumer-keys', variables.consumerId] })
    },
    onError: (err: Error) => showToast('error', 'Revocation Failed', err.message),
  })

  const toggleScope = (scopeId: string) => {
    setSelectedScopes((prev) =>
      prev.includes(scopeId) ? prev.filter((s) => s !== scopeId) : [...prev, scopeId]
    )
  }

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!createConsumerId) {
      showToast('warning', 'Validation Error', 'Please select a consumer.')
      return
    }
    if (!keyLabel.trim()) {
      showToast('warning', 'Validation Error', 'Key label is required.')
      return
    }
    if (selectedScopes.length === 0) {
      showToast('warning', 'Validation Error', 'At least one scope must be selected.')
      return
    }

    createKeyMutation.mutate({
      consumerId: createConsumerId,
      name: keyLabel.trim(),
      scopes: selectedScopes,
      expiresAt: expiresAtInput ? new Date(expiresAtInput).toISOString() : undefined,
    })
  }

  const filteredKeys = (keys || []).filter((k) => {
    const matchesQuery =
      k.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      k.keyPrefix.toLowerCase().includes(searchQuery.toLowerCase()) ||
      k.scopes.some((s) => s.toLowerCase().includes(searchQuery.toLowerCase()))
    return matchesQuery
  })

  const currentConsumer = consumers.find((c) => c.id === activeConsumerId)

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-bold tracking-tight text-silver-frost">API Key Management</h1>
            <Badge variant="slate" className="border-moss text-titanium text-[10px]">
              Ingress Security
            </Badge>
          </div>
          <p className="text-xs text-titanium mt-1">
            Generate and audit cryptographic credentials authorizing gateway ingress traffic
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => refetch()}
            disabled={isFetching}
            className="text-xs border-moss/60 text-titanium hover:text-silver-frost bg-surface"
          >
            <RefreshCw className={`w-3.5 h-3.5 mr-1.5 ${isFetching ? 'animate-spin text-emerald' : ''}`} />
            Refresh
          </Button>
          {canMutate() && (
            <Button
              variant="primary"
              size="sm"
              onClick={() => {
                setCreateConsumerId(selectedConsumerId || consumers[0]?.id || '')
                setIsCreateModalOpen(true)
              }}
              className="text-xs"
            >
              <Plus className="w-3.5 h-3.5 mr-1" />
              Provision API Key
            </Button>
          )}
        </div>
      </div>

      {/* Selector & Search Toolbar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-surface p-3.5 rounded-xl border border-moss/40 shadow-sm">
        <div className="flex items-center gap-2 w-full sm:w-80">
          <label className="text-xs font-semibold text-titanium shrink-0">Consumer:</label>
          <select
            value={activeConsumerId}
            onChange={(e) => setSelectedConsumerId(e.target.value)}
            className="w-full px-2.5 py-1.5 text-xs bg-surface-elevated border border-moss/40 rounded-lg text-silver-frost font-medium focus:outline-none focus:border-emerald"
          >
            {consumers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.plan?.name || 'Standard'})
              </option>
            ))}
          </select>
        </div>

        <div className="relative w-full sm:w-72">
          <Search className="w-4 h-4 text-titanium-muted absolute left-3 top-2.5 pointer-events-none" />
          <input
            type="text"
            placeholder="Search key prefix or scope..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3.5 py-1.5 text-xs bg-surface-elevated border border-moss/40 rounded-lg text-silver-frost placeholder:text-titanium-muted focus:outline-none focus:border-emerald transition-colors"
          />
        </div>
      </div>

      {/* Consumer Quick Info Bar */}
      {currentConsumer && (
        <div className="flex items-center justify-between p-3.5 bg-surface border border-moss/40 rounded-xl text-xs">
          <div className="flex items-center gap-3">
            <span className="font-semibold text-silver-frost">{currentConsumer.name}</span>
            <Badge variant="purple">{currentConsumer.plan?.name}</Badge>
            <span className="text-titanium font-mono hidden sm:inline">{currentConsumer.email}</span>
          </div>
          <Link
            to={`/consumers/${currentConsumer.id}`}
            className="text-emerald font-medium hover:text-emerald/80 flex items-center gap-1"
          >
            Manage Consumer &rarr;
          </Link>
        </div>
      )}

      {/* Keys Table Card */}
      <Card className="bg-surface border border-moss/40 overflow-hidden">
        <CardContent className="p-0">
          {keysLoading || consumersLoading ? (
            <div className="p-6 space-y-4">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : keysError ? (
            <div className="p-12 text-center text-titanium-muted">
              <ShieldAlert className="w-8 h-8 text-crimson mx-auto mb-2" />
              <p className="text-sm font-semibold text-silver-frost">Failed to load API keys</p>
              <p className="text-xs text-titanium mt-1">Check network connectivity and permissions.</p>
            </div>
          ) : filteredKeys.length === 0 ? (
            <div className="p-12 text-center text-titanium-muted">
              <KeyRound className="w-10 h-10 text-titanium-muted/40 mx-auto mb-2" />
              <p className="text-sm font-semibold text-silver-frost">No API keys found</p>
              <p className="text-xs text-titanium mt-1">
                {searchQuery ? 'No keys match the search criteria.' : 'Generate a key for this consumer.'}
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="bg-surface-elevated/70 border-b border-moss/30 text-titanium uppercase tracking-wider font-medium text-[11px]">
                  <tr>
                    <th className="px-5 py-3">Key Label</th>
                    <th className="px-5 py-3">Key Prefix</th>
                    <th className="px-5 py-3">Status</th>
                    <th className="px-5 py-3">Scopes</th>
                    <th className="px-5 py-3">Created</th>
                    <th className="px-5 py-3">Expires</th>
                    <th className="px-5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-moss/20 text-silver-frost">
                  {filteredKeys.map((k) => (
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
                              className="px-1.5 py-0.5 text-[10px] font-mono bg-surface-elevated text-silver-frost rounded border border-moss/30"
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
                        {k.status === 'ACTIVE' && canMutate() ? (
                          <Button
                            variant="danger"
                            size="sm"
                            onClick={() => setRevokeTarget({ consumerId: activeConsumerId, key: k })}
                            className="text-[11px] py-1 px-2"
                          >
                            <Trash2 className="w-3 h-3 mr-1" />
                            Revoke
                          </Button>
                        ) : k.revocationReason ? (
                          <span
                            className="text-[11px] text-titanium-muted italic"
                            title={`Reason: ${k.revocationReason}`}
                          >
                            {k.revocationReason}
                          </span>
                        ) : (
                          <span className="text-[11px] text-titanium-muted italic">Inactive</span>
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

      {/* 1. Modal: Provision API Key */}
      <Modal
        isOpen={isCreateOpen}
        onClose={handleCloseCreate}
        title="Provision Cryptographic API Key"
        description="Generates a high-entropy key credential. The plaintext key will be shown ONCE upon generation."
      >
        <form onSubmit={handleCreateSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-titanium mb-1">Target Consumer Organization</label>
            <select
              value={createConsumerId}
              onChange={(e) => setCreateConsumerId(e.target.value)}
              className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
              required
            >
              <option value="">Select a consumer...</option>
              {consumers.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.email})
                </option>
              ))}
            </select>
          </div>

          <Input
            label="Key Identifier / Label"
            placeholder="e.g. Production Ingress Key (Live)"
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
            label="Expiration Date (Optional)"
            type="datetime-local"
            value={expiresAtInput}
            onChange={(e) => setExpiresAtInput(e.target.value)}
          />

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" type="button" onClick={handleCloseCreate}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={createKeyMutation.isPending}>
              {createKeyMutation.isPending ? 'Generating Key...' : 'Generate API Key'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* 2. Modal: Revoke API Key */}
      {revokeTarget && (
        <Modal
          isOpen={!!revokeTarget}
          onClose={() => setRevokeTarget(null)}
          title="Revoke API Key"
          description="Permanently invalidate this cryptographic key. Downstream calls will be immediately rejected."
        >
          <div className="space-y-4">
            <div className="p-3 bg-crimson/10 border border-crimson/30 rounded-lg text-xs text-crimson leading-relaxed">
              <strong>Warning:</strong> Key <strong>{revokeTarget.key.name}</strong> (
              <span className="font-mono">{revokeTarget.key.keyPrefix}</span>) will be permanently revoked. This
              action is irreversible and halts all incoming traffic authenticated with this secret.
            </div>

            <div>
              <label className="block text-xs font-semibold text-titanium mb-1">Revocation Audit Reason</label>
              <textarea
                value={revocationReason}
                onChange={(e) => setRevocationReason(e.target.value)}
                placeholder="Reason for key invalidation (e.g. Credential rotated, Tenant deactivated)..."
                rows={3}
                maxLength={255}
                className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-crimson"
              />
              <span className="text-[10px] text-titanium-muted">{revocationReason.length} / 255 characters</span>
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" onClick={() => setRevokeTarget(null)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                onClick={() =>
                  revokeKeyMutation.mutate({
                    consumerId: revokeTarget.consumerId,
                    keyId: revokeTarget.key.id,
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

      {/* 3. Secure One-Time Secret Reveal Modal */}
      {createdKeyPlaintext && (
        <OneTimeSecretModal
          isOpen={secretModalOpen}
          onClose={() => {
            // SECURE WIPE
            setCreatedKeyPlaintext(null)
            setSecretModalOpen(false)
          }}
          plaintextKey={createdKeyPlaintext}
          keyName={createdKeyName}
          keyPrefix={createdKeyPrefix}
        />
      )}
    </div>
  )
}
export default ApiKeys
