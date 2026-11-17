import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { consumerService } from '@/services/consumerService'
import { apiKeyService } from '@/services/apiKeyService'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
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
  AlertTriangle,
  ShieldAlert,
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

  // Create Key Modal
  const [isCreateOpen, setIsCreateOpen] = useState<boolean>(false)
  const [createConsumerId, setCreateConsumerId] = useState<string>('')
  const [keyLabel, setKeyLabel] = useState<string>('')
  const [selectedScopes, setSelectedScopes] = useState<string[]>(['gateway:invoke'])
  const [expiresAtInput, setExpiresAtInput] = useState<string>('')

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
      setIsCreateOpen(false)
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
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">API Key Management</h1>
          <p className="text-xs text-slate-500 mt-1">
            Generate and audit cryptographic credentials authorizing gateway ingress traffic
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => refetch()}
            disabled={isFetching}
            className="text-xs"
          >
            <RefreshCw className={`w-3.5 h-3.5 mr-1.5 ${isFetching ? 'animate-spin' : ''}`} />
            Refresh
          </Button>
          {canMutate() && (
            <Button
              variant="primary"
              size="sm"
              onClick={() => {
                setCreateConsumerId(selectedConsumerId || (consumers[0]?.id || ''))
                setIsCreateOpen(true)
              }}
              className="text-xs shadow-sm bg-indigo-600 hover:bg-indigo-500"
            >
              <Plus className="w-3.5 h-3.5 mr-1" />
              Provision API Key
            </Button>
          )}
        </div>
      </div>

      {/* Selector & Search Toolbar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-white p-3.5 rounded-xl border border-slate-200/80 shadow-xs">
        <div className="flex items-center gap-2 w-full sm:w-80">
          <label className="text-xs font-semibold text-slate-600 shrink-0">Consumer:</label>
          <select
            value={activeConsumerId}
            onChange={(e) => setSelectedConsumerId(e.target.value)}
            className="w-full px-2.5 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg text-slate-900 font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
          >
            {consumers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.plan?.name || 'Standard'})
              </option>
            ))}
          </select>
        </div>

        <div className="relative w-full sm:w-72">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-2.5 pointer-events-none" />
          <input
            type="text"
            placeholder="Search key prefix or scope..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3.5 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20 transition-colors"
          />
        </div>
      </div>

      {/* Consumer Quick Info Bar */}
      {currentConsumer && (
        <div className="flex items-center justify-between p-3.5 bg-indigo-50/50 border border-indigo-100 rounded-xl text-xs">
          <div className="flex items-center gap-3">
            <span className="font-semibold text-slate-900">{currentConsumer.name}</span>
            <Badge variant="purple">{currentConsumer.plan?.name}</Badge>
            <span className="text-slate-500 font-mono hidden sm:inline">{currentConsumer.email}</span>
          </div>
          <Link
            to={`/consumers/${currentConsumer.id}`}
            className="text-indigo-600 font-medium hover:text-indigo-800 flex items-center gap-1"
          >
            Manage Consumer &rarr;
          </Link>
        </div>
      )}

      {/* Keys Table Card */}
      <Card>
        <CardContent className="p-0">
          {keysLoading || consumersLoading ? (
            <div className="p-6 space-y-4">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : keysError ? (
            <div className="p-12 text-center text-slate-400">
              <ShieldAlert className="w-8 h-8 text-rose-500 mx-auto mb-2" />
              <p className="text-sm font-semibold text-slate-800">Failed to load API keys</p>
            </div>
          ) : filteredKeys.length === 0 ? (
            <div className="p-12 text-center text-slate-400">
              <KeyRound className="w-10 h-10 text-slate-300 mx-auto mb-2" />
              <p className="text-sm font-semibold text-slate-800">No API keys found</p>
              <p className="text-xs text-slate-500 mt-1">
                {searchQuery ? 'No keys match the search criteria.' : 'Generate a key for this consumer.'}
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-700">
                <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3.5">Key Label</th>
                    <th className="px-6 py-3.5">Key Prefix</th>
                    <th className="px-6 py-3.5">Status</th>
                    <th className="px-6 py-3.5">Scopes</th>
                    <th className="px-6 py-3.5">Last Used</th>
                    <th className="px-6 py-3.5">Expires</th>
                    <th className="px-6 py-3.5 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 bg-white">
                  {filteredKeys.map((k) => (
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
                            onClick={() =>
                              setRevokeTarget({
                                consumerId: activeConsumerId,
                                key: k,
                              })
                            }
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

      {/* Modal: Provision API Key */}
      <Modal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        title="Provision Consumer API Key"
        description="Generates an authentication key. Plaintext credential is displayed exactly once."
      >
        <form onSubmit={handleCreateSubmit} className="space-y-4">
          <Select
            label="Target Consumer"
            value={createConsumerId}
            onChange={(e) => setCreateConsumerId(e.target.value)}
            required
          >
            {consumers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.email})
              </option>
            ))}
          </Select>

          <Input
            label="API Key Label"
            placeholder="e.g. Backend Automation Worker"
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
            label="Expiration Timestamp (Optional UTC)"
            type="datetime-local"
            value={expiresAtInput}
            onChange={(e) => setExpiresAtInput(e.target.value)}
            helperText="Leave blank for a non-expiring key"
          />

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" type="button" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" isLoading={createKeyMutation.isPending}>
              Generate Key
            </Button>
          </div>
        </form>
      </Modal>

      {/* ONE-TIME SECRET MODAL */}
      <OneTimeSecretModal
        isOpen={secretModalOpen}
        onClose={() => {
          setCreatedKeyPlaintext(null)
          setSecretModalOpen(false)
        }}
        keyName={createdKeyName}
        keyPrefix={createdKeyPrefix}
        plaintextKey={createdKeyPlaintext}
      />

      {/* Modal: Revoke API Key */}
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
              Are you sure you want to revoke key <strong>{revokeTarget?.key.name}</strong> (prefix:{' '}
              <code>{revokeTarget?.key.keyPrefix}</code>)? Gateway requests using this key will be rejected with HTTP
              401.
            </div>
          </div>

          <Input
            label="Revocation Reason (Optional)"
            placeholder="e.g. Scheduled rotation"
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
                revokeKeyMutation.mutate({
                  consumerId: revokeTarget.consumerId,
                  keyId: revokeTarget.key.id,
                  reason: revocationReason,
                })
              }
              isLoading={revokeKeyMutation.isPending}
            >
              Confirm Revocation
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
