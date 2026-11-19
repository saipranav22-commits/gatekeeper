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
import { Select } from '@/components/ui/Select'
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
} from 'lucide-react'

export const Consumers: React.FC = () => {
  const { canMutate } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  const [page, setPage] = useState<number>(0)
  const [pageSize] = useState<number>(20)
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

  // 1. Fetch Consumers
  const {
    data: consumerPage,
    isLoading,
    isFetching,
    error,
    refetch,
  } = useQuery({
    queryKey: ['consumers', page, pageSize],
    queryFn: () => consumerService.listConsumers(page, pageSize, 'createdAt', 'desc'),
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

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">API Consumers</h1>
          <p className="text-xs text-slate-500 mt-1">
            Manage authenticated API consumer organizations, rate limits, and lifecycle states
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
                if (plans && plans.length > 0) {
                  setNewConsumerPlanId(plans[0].id)
                }
                setIsCreateModalOpen(true)
              }}
              className="text-xs shadow-sm bg-indigo-600 hover:bg-indigo-500"
            >
              <Plus className="w-3.5 h-3.5 mr-1" />
              Register Consumer
            </Button>
          )}
        </div>
      </div>

      {/* Filters and Search Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-white p-3.5 rounded-xl border border-slate-200/80 shadow-xs">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-2.5 pointer-events-none" />
          <input
            type="text"
            placeholder="Search by name, email, or UUID..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3.5 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg text-slate-800 placeholder:text-slate-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-colors"
          />
        </div>
        <div className="flex items-center gap-2 w-full sm:w-auto">
          <span className="text-xs text-slate-500 font-medium">Status:</span>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="px-2.5 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
          >
            <option value="ALL">All Statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="SUSPENDED">Suspended</option>
            <option value="DEACTIVATED">Deactivated</option>
          </select>
        </div>
      </div>

      {/* Consumers Table Card */}
      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="p-6 space-y-4">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : error ? (
            <div className="p-12 text-center text-slate-400">
              <ShieldAlert className="w-8 h-8 text-rose-500 mx-auto mb-2" />
              <p className="text-sm font-semibold text-slate-800">Failed to load consumers</p>
              <p className="text-xs text-slate-500 mt-1">Please ensure backend services are running and authenticated</p>
            </div>
          ) : filteredConsumers.length === 0 ? (
            <div className="p-12 text-center text-slate-400">
              <Users className="w-10 h-10 text-slate-300 mx-auto mb-2" />
              <p className="text-sm font-semibold text-slate-800">No consumers found</p>
              <p className="text-xs text-slate-500 mt-1">
                {searchQuery ? 'No consumers match your search filter.' : 'Register an API consumer to begin.'}
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-700">
                <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3.5">Consumer</th>
                    <th className="px-6 py-3.5">Plan</th>
                    <th className="px-6 py-3.5">Status</th>
                    <th className="px-6 py-3.5">Registered</th>
                    <th className="px-6 py-3.5 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 bg-white">
                  {filteredConsumers.map((c) => (
                    <tr key={c.id} className="hover:bg-slate-50/70 transition-colors">
                      <td className="px-6 py-4">
                        <Link
                          to={`/consumers/${c.id}`}
                          className="font-semibold text-slate-900 hover:text-indigo-600 transition-colors"
                        >
                          {c.name}
                        </Link>
                        <div className="text-xs text-slate-400 font-mono mt-0.5">{c.email}</div>
                      </td>
                      <td className="px-6 py-4">
                        <Badge variant="purple">{c.plan?.name || 'Unassigned'}</Badge>
                      </td>
                      <td className="px-6 py-4">
                        {c.status === 'ACTIVE' && <Badge variant="success">Active</Badge>}
                        {c.status === 'SUSPENDED' && <Badge variant="warning">Suspended</Badge>}
                        {c.status === 'DEACTIVATED' && <Badge variant="slate">Deactivated</Badge>}
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-500">{formatDate(c.createdAt)}</td>
                      <td className="px-6 py-4 text-right">
                        <div className="inline-flex items-center gap-1.5 justify-end">
                          <Link
                            to={`/consumers/${c.id}`}
                            className="p-1.5 text-slate-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-md transition-colors"
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
                                className="px-2 py-1 text-xs font-medium text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded-md transition-colors"
                              >
                                Plan
                              </button>

                              {c.status === 'ACTIVE' ? (
                                <button
                                  onClick={() => suspendMutation.mutate(c.id)}
                                  className="p-1.5 text-amber-600 hover:bg-amber-50 rounded-md transition-colors"
                                  title="Suspend Consumer"
                                >
                                  <PauseCircle className="w-4 h-4" />
                                </button>
                              ) : (
                                <button
                                  onClick={() => reactivateMutation.mutate(c.id)}
                                  className="p-1.5 text-emerald-600 hover:bg-emerald-50 rounded-md transition-colors"
                                  title="Reactivate Consumer"
                                >
                                  <PlayCircle className="w-4 h-4" />
                                </button>
                              )}

                              <button
                                onClick={() => setDeleteTarget(c)}
                                className="p-1.5 text-rose-500 hover:bg-rose-50 rounded-md transition-colors"
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

          {/* Pagination Controls */}
          {consumerPage && consumerPage.totalPages > 1 && (
            <div className="p-4 border-t border-slate-100 flex items-center justify-between text-xs text-slate-500">
              <div>
                Showing page {consumerPage.number + 1} of {consumerPage.totalPages} ({consumerPage.totalElements} total
                consumers)
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={consumerPage.first}
                  className="text-xs py-1 px-2.5"
                >
                  <ChevronLeft className="w-3.5 h-3.5 mr-1" />
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={consumerPage.last}
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

      {/* Modal: Register Consumer */}
      <Modal
        isOpen={isCreateOpen}
        onClose={handleCloseCreate}
        title="Register API Consumer"
        description="Create a new client entity associated with a subscription rate plan."
      >
        <form onSubmit={handleCreateSubmit} className="space-y-4">
          <Input
            label="Organization or Client Name"
            placeholder="e.g. Acme Corporation"
            value={newConsumerName}
            onChange={(e) => setNewConsumerName(e.target.value)}
            required
          />
          <Input
            label="Contact Email"
            type="email"
            placeholder="e.g. api@acme.com"
            value={newConsumerEmail}
            onChange={(e) => setNewConsumerEmail(e.target.value)}
            required
          />
          <Select
            label="Initial Subscription Plan"
            value={newConsumerPlanId}
            onChange={(e) => setNewConsumerPlanId(e.target.value)}
            required
          >
            {plans?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} — {p.bucketCapacity} burst tokens ({p.refillTokens} / {p.refillPeriodSeconds}s)
              </option>
            ))}
          </Select>

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" type="button" onClick={handleCloseCreate}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" isLoading={createMutation.isPending}>
              Create Consumer
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Change Subscription Plan */}
      <Modal
        isOpen={!!changePlanConsumer}
        onClose={() => setChangePlanConsumer(null)}
        title="Migrate Subscription Plan"
        description={`Update token bucket rate limits for ${changePlanConsumer?.name}`}
      >
        <form onSubmit={handleChangePlanSubmit} className="space-y-4">
          <div className="p-3 bg-slate-50 rounded-lg text-xs space-y-1 text-slate-600">
            <div>
              Current Plan: <strong className="text-slate-900">{changePlanConsumer?.plan?.name || 'None'}</strong>
            </div>
            <div>
              Capacity: {changePlanConsumer?.plan?.bucketCapacity} tokens • Refill:{' '}
              {changePlanConsumer?.plan?.refillTokens} / {changePlanConsumer?.plan?.refillPeriodSeconds}s
            </div>
          </div>

          <Select
            label="Target Plan"
            value={selectedNewPlanId}
            onChange={(e) => setSelectedNewPlanId(e.target.value)}
            required
          >
            {plans?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} — {p.bucketCapacity} tokens ({p.refillTokens}/{p.refillPeriodSeconds}s)
              </option>
            ))}
          </Select>

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" type="button" onClick={() => setChangePlanConsumer(null)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" isLoading={changePlanMutation.isPending}>
              Apply Migration
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Soft Delete Confirmation */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        title="Confirm Soft Deletion"
        description="Preserve historical audit logs while permanently deauthorizing access."
      >
        <div className="space-y-4">
          <div className="p-3.5 bg-rose-50 border border-rose-200 rounded-lg text-rose-900 text-xs flex items-start gap-2.5">
            <ShieldAlert className="w-5 h-5 text-rose-600 shrink-0 mt-0.5" />
            <div className="leading-relaxed">
              Are you sure you want to soft-delete <strong>{deleteTarget?.name}</strong>? All associated API keys will
              immediately be invalidated and gateway requests will be rejected with HTTP 401. Historical audit events
              are preserved.
            </div>
          </div>

          <div className="pt-3 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
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
