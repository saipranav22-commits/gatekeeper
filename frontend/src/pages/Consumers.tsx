import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { consumerService } from '@/services/consumerService'
import { planService } from '@/services/planService'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatDate } from '@/lib/utils'
import { ConsumerDto } from '@/types/consumer'
import {
  Users,
  Plus,
  Search,
  ChevronLeft,
  ChevronRight,
  ShieldAlert,
  PauseCircle,
  PlayCircle,
  Trash2,
  RefreshCw,
  ExternalLink,
  ArrowUpDown,
} from 'lucide-react'

export const Consumers: React.FC = () => {
  const { canMutate } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  const [page, setPage] = useState<number>(0)
  const [pageSize] = useState<number>(20)
  const [sortBy, setSortBy] = useState<string>('createdAt')
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc')
  const [searchQuery, setSearchQuery] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [searchParams, setSearchParams] = useSearchParams()

  // Modals state
  const [isCreateModalOpen, setIsCreateModalOpen] = useState<boolean>(false)
  const isCreateOpen = isCreateModalOpen || (searchParams.get('action') === 'register' && canMutate())
  const [newConsumerName, setNewConsumerName] = useState<string>('')
  const [newConsumerEmail, setNewConsumerEmail] = useState<string>('')
  const [newConsumerPlanId, setNewConsumerPlanId] = useState<string>('')

  const handleCloseCreate = () => {
    setIsCreateModalOpen(false)
    if (searchParams.get('action') === 'register') {
      const newParams = new URLSearchParams(searchParams)
      newParams.delete('action')
      setSearchParams(newParams, { replace: true })
    }
  }

  // Plan change modal
  const [changePlanConsumer, setChangePlanConsumer] = useState<ConsumerDto | null>(null)
  const [selectedNewPlanId, setSelectedNewPlanId] = useState<string>('')

  // Delete confirm modal
  const [deleteTarget, setDeleteTarget] = useState<ConsumerDto | null>(null)

  // 1. Fetch Consumers from real backend
  const {
    data: consumerPage,
    isLoading,
    isFetching,
    error,
    refetch,
  } = useQuery({
    queryKey: ['consumers', page, pageSize, sortBy, sortDirection],
    queryFn: () => consumerService.listConsumers(page, pageSize, sortBy, sortDirection),
  })

  // 2. Fetch Subscription Plans
  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => planService.listPlans(),
  })

  // Mutations
  const createMutation = useMutation({
    mutationFn: consumerService.createConsumer,
    onSuccess: (newConsumer) => {
      showToast('success', 'Consumer Created', `Consumer '${newConsumer.name}' registered successfully.`)
      handleCloseCreate()
      setNewConsumerName('')
      setNewConsumerEmail('')
      setNewConsumerPlanId('')
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
    },
    onError: (err: Error) => {
      showToast('error', 'Creation Failed', err.message || 'Failed to create consumer.')
    },
  })

  const suspendMutation = useMutation({
    mutationFn: consumerService.suspendConsumer,
    onSuccess: (updated) => {
      showToast('warning', 'Consumer Suspended', `Access suspended for '${updated.name}'.`)
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
    },
    onError: (err: Error) => {
      showToast('error', 'Action Failed', err.message)
    },
  })

  const reactivateMutation = useMutation({
    mutationFn: consumerService.reactivateConsumer,
    onSuccess: (updated) => {
      showToast('success', 'Consumer Reactivated', `Access restored for '${updated.name}'.`)
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
    },
    onError: (err: Error) => {
      showToast('error', 'Action Failed', err.message)
    },
  })

  const changePlanMutation = useMutation({
    mutationFn: ({ consumerId, planId }: { consumerId: string; planId: string }) =>
      consumerService.changePlan(consumerId, planId),
    onSuccess: (updated) => {
      showToast('success', 'Plan Updated', `Consumer '${updated.name}' migrated to ${updated.plan?.name}.`)
      setChangePlanConsumer(null)
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
    },
    onError: (err: Error) => {
      showToast('error', 'Migration Failed', err.message)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: consumerService.deleteConsumer,
    onSuccess: () => {
      showToast('success', 'Consumer Soft-Deleted', 'Consumer marked as deleted while preserving audit history.')
      setDeleteTarget(null)
      queryClient.invalidateQueries({ queryKey: ['consumers'] })
    },
    onError: (err: Error) => {
      showToast('error', 'Deletion Failed', err.message)
    },
  })

  // Filter items in memory based on search query
  const filteredConsumers = (consumerPage?.content || []).filter((c) => {
    const matchesSearch =
      c.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      c.email.toLowerCase().includes(searchQuery.toLowerCase()) ||
      c.id.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'ALL' || c.status === statusFilter
    return matchesSearch && matchesStatus
  })

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!newConsumerName || !newConsumerEmail || !newConsumerPlanId) {
      showToast('warning', 'Validation Error', 'Please complete all required fields.')
      return
    }
    createMutation.mutate({
      name: newConsumerName,
      email: newConsumerEmail,
      planId: newConsumerPlanId,
    })
  }

  const handleChangePlanSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!changePlanConsumer || !selectedNewPlanId) return
    changePlanMutation.mutate({
      consumerId: changePlanConsumer.id,
      planId: selectedNewPlanId,
    })
  }

  const toggleSort = (col: string) => {
    if (sortBy === col) {
      setSortDirection((prev) => (prev === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortBy(col)
      setSortDirection('desc')
    }
    setPage(0)
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-bold tracking-tight text-silver-frost">API Consumers</h1>
            <Badge variant="slate" className="border-moss text-titanium text-[10px]">
              Directory
            </Badge>
          </div>
          <p className="text-xs text-titanium mt-1">
            Manage authenticated API consumer organizations, rate limits, and lifecycle states
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
                if (plans && plans.length > 0) {
                  setNewConsumerPlanId(plans[0].id)
                }
                setIsCreateModalOpen(true)
              }}
              className="text-xs"
            >
              <Plus className="w-3.5 h-3.5 mr-1" />
              Register Consumer
            </Button>
          )}
        </div>
      </div>

      {/* Filters and Search Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-surface p-3.5 rounded-xl border border-moss/40 shadow-sm">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 text-titanium-muted absolute left-3 top-2.5 pointer-events-none" />
          <input
            type="text"
            placeholder="Search by name, email, or UUID..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3.5 py-1.5 text-xs bg-surface-elevated border border-moss/40 rounded-lg text-silver-frost placeholder:text-titanium-muted focus:outline-none focus:border-emerald transition-colors"
          />
        </div>
        <div className="flex items-center gap-3 w-full sm:w-auto">
          <div className="flex items-center gap-1.5 text-xs text-titanium">
            <span className="font-medium">Status:</span>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="px-2.5 py-1.5 text-xs bg-surface-elevated border border-moss/40 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="SUSPENDED">Suspended</option>
              <option value="DEACTIVATED">Deactivated</option>
            </select>
          </div>

          <div className="flex items-center gap-1.5 text-xs text-titanium">
            <span className="font-medium">Sort:</span>
            <select
              value={sortBy}
              onChange={(e) => {
                setSortBy(e.target.value)
                setPage(0)
              }}
              className="px-2.5 py-1.5 text-xs bg-surface-elevated border border-moss/40 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
            >
              <option value="createdAt">Date Created</option>
              <option value="name">Name</option>
              <option value="email">Email</option>
              <option value="status">Status</option>
            </select>
            <button
              onClick={() => setSortDirection((prev) => (prev === 'asc' ? 'desc' : 'asc'))}
              className="p-1.5 border border-moss/40 rounded-lg bg-surface-elevated hover:bg-surface text-titanium hover:text-silver-frost"
              title="Toggle sort direction"
            >
              <ArrowUpDown className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      {/* Consumers Table Card */}
      <Card className="bg-surface border border-moss/40 overflow-hidden">
        <CardContent className="p-0">
          {isLoading ? (
            <div className="p-6 space-y-4">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : error ? (
            <div className="p-12 text-center text-titanium-muted">
              <ShieldAlert className="w-8 h-8 text-crimson mx-auto mb-2" />
              <p className="text-sm font-semibold text-silver-frost">Failed to load consumers</p>
              <p className="text-xs text-titanium mt-1">Please ensure backend services are running and authenticated</p>
            </div>
          ) : filteredConsumers.length === 0 ? (
            <div className="p-12 text-center text-titanium-muted">
              <Users className="w-10 h-10 text-titanium-muted/40 mx-auto mb-2" />
              <p className="text-sm font-semibold text-silver-frost">No consumers found</p>
              <p className="text-xs text-titanium mt-1">
                {searchQuery ? 'No consumers match your search filter.' : 'Register an API consumer to begin.'}
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="bg-surface-elevated/70 border-b border-moss/30 text-titanium uppercase tracking-wider font-medium text-[11px]">
                  <tr>
                    <th className="px-5 py-3 cursor-pointer hover:text-silver-frost" onClick={() => toggleSort('name')}>
                      Consumer
                    </th>
                    <th className="px-5 py-3">Plan</th>
                    <th className="px-5 py-3 cursor-pointer hover:text-silver-frost" onClick={() => toggleSort('status')}>
                      Status
                    </th>
                    <th className="px-5 py-3 cursor-pointer hover:text-silver-frost" onClick={() => toggleSort('createdAt')}>
                      Registered
                    </th>
                    <th className="px-5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-moss/20 text-silver-frost">
                  {filteredConsumers.map((c) => (
                    <tr key={c.id} className="hover:bg-surface-elevated/40 transition-colors">
                      <td className="px-5 py-3.5">
                        <Link
                          to={`/consumers/${c.id}`}
                          className="font-semibold text-silver-frost hover:text-emerald transition-colors"
                        >
                          {c.name}
                        </Link>
                        <div className="text-[11px] text-titanium font-mono mt-0.5">{c.email}</div>
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge variant="purple">{c.plan?.name || 'Unassigned'}</Badge>
                      </td>
                      <td className="px-5 py-3.5">
                        {c.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                        {c.status === 'SUSPENDED' && <Badge variant="warning">Suspended</Badge>}
                        {c.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
                      </td>
                      <td className="px-5 py-3.5 text-titanium font-mono text-[11px]">{formatDate(c.createdAt)}</td>
                      <td className="px-5 py-3.5 text-right">
                        <div className="inline-flex items-center gap-1.5 justify-end">
                          <Link
                            to={`/consumers/${c.id}`}
                            className="p-1.5 text-titanium hover:text-emerald hover:bg-surface-elevated rounded-md transition-colors"
                            title="View Consumer Details"
                          >
                            <ExternalLink className="w-4 h-4" />
                          </Link>

                          {canMutate() && (
                            <>
                              <button
                                onClick={() => {
                                  setChangePlanConsumer(c)
                                  setSelectedNewPlanId(c.plan?.id || (plans ? plans[0]?.id : ''))
                                }}
                                className="px-2 py-1 text-[11px] font-medium text-titanium hover:text-silver-frost bg-surface-elevated hover:bg-surface-elevated/80 border border-moss/30 rounded-md transition-colors"
                              >
                                Plan
                              </button>

                              {c.status === 'ACTIVE' ? (
                                <button
                                  onClick={() => suspendMutation.mutate(c.id)}
                                  className="p-1.5 text-amber hover:bg-amber/10 rounded-md transition-colors"
                                  title="Suspend Consumer"
                                >
                                  <PauseCircle className="w-4 h-4" />
                                </button>
                              ) : (
                                <button
                                  onClick={() => reactivateMutation.mutate(c.id)}
                                  className="p-1.5 text-emerald hover:bg-emerald/10 rounded-md transition-colors"
                                  title="Reactivate Consumer"
                                >
                                  <PlayCircle className="w-4 h-4" />
                                </button>
                              )}

                              <button
                                onClick={() => setDeleteTarget(c)}
                                className="p-1.5 text-crimson hover:bg-crimson/10 rounded-md transition-colors"
                                title="Soft-Delete Consumer"
                              >
                                <Trash2 className="w-4 h-4" />
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Pagination Footer */}
          {consumerPage && consumerPage.totalPages > 1 && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-moss/30 bg-surface-elevated/40 text-xs text-titanium">
              <div>
                Showing page <span className="font-mono text-silver-frost">{page + 1}</span> of{' '}
                <span className="font-mono text-silver-frost">{consumerPage.totalPages}</span> (
                <span className="font-mono text-silver-frost">{consumerPage.totalElements}</span> total consumers)
              </div>
              <div className="flex items-center gap-1.5">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="p-1.5 bg-surface border-moss/40"
                >
                  <ChevronLeft className="w-4 h-4" />
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => (consumerPage.last ? p : p + 1))}
                  disabled={consumerPage.last}
                  className="p-1.5 bg-surface border-moss/40"
                >
                  <ChevronRight className="w-4 h-4" />
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 1. Modal: Register Consumer */}
      <Modal
        isOpen={isCreateOpen}
        onClose={handleCloseCreate}
        title="Register API Consumer"
        description="Onboard an organization or external client with an assigned rate-limiting plan."
      >
        <form onSubmit={handleCreateSubmit} className="space-y-4">
          <Input
            label="Consumer Organization Name"
            placeholder="e.g. Acme Corporation"
            value={newConsumerName}
            onChange={(e) => setNewConsumerName(e.target.value)}
            required
          />
          <Input
            label="Primary Contact Email"
            type="email"
            placeholder="developer@acme.com"
            value={newConsumerEmail}
            onChange={(e) => setNewConsumerEmail(e.target.value)}
            required
          />
          <div>
            <label className="block text-xs font-semibold text-titanium mb-1">Subscription Plan Tier</label>
            <select
              value={newConsumerPlanId}
              onChange={(e) => setNewConsumerPlanId(e.target.value)}
              className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
              required
            >
              {plans?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.bucketCapacity} tokens burst)
                </option>
              ))}
            </select>
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" type="button" onClick={handleCloseCreate}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Registering...' : 'Register Consumer'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* 2. Modal: Change Subscription Plan */}
      {changePlanConsumer && (
        <Modal
          isOpen={!!changePlanConsumer}
          onClose={() => setChangePlanConsumer(null)}
          title={`Migrate Plan: ${changePlanConsumer.name}`}
          description="Adjust the assigned rate-limiting bucket. Changes propagate immediately to Gateway Redis clusters."
        >
          <form onSubmit={handleChangePlanSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-titanium mb-1">Select Target Plan</label>
              <select
                value={selectedNewPlanId}
                onChange={(e) => setSelectedNewPlanId(e.target.value)}
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
              <Button variant="outline" type="button" onClick={() => setChangePlanConsumer(null)}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" disabled={changePlanMutation.isPending}>
                {changePlanMutation.isPending ? 'Updating...' : 'Migrate Plan'}
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* 3. Modal: Soft Delete Confirmation */}
      {deleteTarget && (
        <Modal
          isOpen={!!deleteTarget}
          onClose={() => setDeleteTarget(null)}
          title="Confirm Consumer Soft-Deletion"
          description="Mark this consumer as deleted. All active API keys will be immediately halted."
        >
          <div className="space-y-4">
            <div className="p-3 bg-crimson/10 border border-crimson/30 rounded-lg text-xs text-crimson leading-relaxed">
              <strong>Warning:</strong> Consumer <strong>{deleteTarget.name}</strong> ({deleteTarget.email}) and its
              associated keys will be deactivated. Audit telemetry records in PostgreSQL and Redis will be preserved.
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" onClick={() => setDeleteTarget(null)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                onClick={() => deleteMutation.mutate(deleteTarget.id)}
                disabled={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? 'Deleting...' : 'Soft-Delete Consumer'}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
export default Consumers
