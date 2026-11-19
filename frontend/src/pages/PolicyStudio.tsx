import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { policyService } from '@/services/policyService'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatDate } from '@/lib/utils'
import {
  PolicyRule,
  PolicySimulationResponse,
  PolicyReplayResponse,
} from '@/types/policy'
import {
  ShieldCheck,
  Play,
  History,
  RotateCcw,
  CheckCircle2,
  XCircle,
  Plus,
  RefreshCw,
  GitCompare,
  Eye,
  AlertTriangle,
  Lock,
  ListOrdered,
  Sparkles,
  Zap,
} from 'lucide-react'

type StudioTab = 'rules' | 'simulation' | 'replay' | 'compare' | 'audit'

export const PolicyStudio: React.FC = () => {
  const { canMutate } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()

  // Selected policy state
  const [selectedPolicyKey, setSelectedPolicyKey] = useState<string>('')
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<number | null>(null)
  const [activeTab, setActiveTab] = useState<StudioTab>('rules')

  // Modals state
  const [isCreateDefinitionOpen, setIsCreateDefinitionOpen] = useState<boolean>(false)
  const [newDefKey, setNewDefKey] = useState<string>('')
  const [newDefName, setNewDefName] = useState<string>('')
  const [newDefDesc, setNewDefDesc] = useState<string>('')

  const [isCreateVersionOpen, setIsCreateVersionOpen] = useState<boolean>(false)
  const [newVersionDesc, setNewVersionDesc] = useState<string>('')
  const [newVersionRulesJson, setNewVersionRulesJson] = useState<string>('')
  const [jsonValidationError, setJsonValidationError] = useState<string | null>(null)

  const [isActivateModalOpen, setIsActivateModalOpen] = useState<boolean>(false)
  const [activateTargetVersion, setActivateTargetVersion] = useState<number | null>(null)
  const [activationReason, setActivationReason] = useState<string>('')

  const [isRollbackModalOpen, setIsRollbackModalOpen] = useState<boolean>(false)
  const [rollbackTargetVersion, setRollbackTargetVersion] = useState<string>('')
  const [rollbackReason, setRollbackReason] = useState<string>('')

  const [isShadowModalOpen, setIsShadowModalOpen] = useState<boolean>(false)
  const [shadowTargetVersion, setShadowTargetVersion] = useState<string>('')

  // Simulation Form State
  const [simRouteId, setSimRouteId] = useState<string>('/api/v1/orders')
  const [simHttpMethod, setSimHttpMethod] = useState<string>('POST')
  const [simConsumerPlan, setSimConsumerPlan] = useState<string>('STARTER')
  const [simScopes, setSimScopes] = useState<string>('gateway:invoke')
  const [simClientIp, setSimClientIp] = useState<string>('192.168.1.100')
  const [simResult, setSimResult] = useState<PolicySimulationResponse | null>(null)

  // Replay Form State
  const [replaySampleLimit, setReplaySampleLimit] = useState<number>(100)
  const [replaySinceHours, setReplaySinceHours] = useState<number>(24)
  const [replayResult, setReplayResult] = useState<PolicyReplayResponse | null>(null)

  // Comparison State
  const [compareTargetVersion, setCompareTargetVersion] = useState<number | null>(null)

  // 1. Fetch all Policy Definitions
  const {
    data: definitions,
    isLoading: defsLoading,
    refetch: refetchDefs,
    isFetching: isFetchingDefs,
  } = useQuery({
    queryKey: ['policy-definitions'],
    queryFn: () => policyService.listDefinitions(),
  })

  // Set default policy key if none selected
  const defsList = Array.isArray(definitions) ? definitions : []
  const activeKey = selectedPolicyKey || (defsList.length > 0 ? defsList[0].policyKey : '')
  const currentDef = defsList.find((d) => d.policyKey === activeKey)

  // 2. Fetch Versions for selected policy definition
  const {
    data: versions,
    refetch: refetchVersions,
  } = useQuery({
    queryKey: ['policy-versions', activeKey],
    queryFn: () => policyService.listVersions(activeKey),
    enabled: !!activeKey,
  })

  const versionsList = Array.isArray(versions) ? versions : []

  // Selected Version for rule inspection
  const effectiveVersionNumber =
    selectedVersionNumber != null
      ? selectedVersionNumber
      : currentDef?.activeVersionNumber != null
      ? currentDef.activeVersionNumber
      : versionsList.length > 0
      ? versionsList[0].versionNumber
      : null

  const { data: currentVersion, isLoading: currentVersionLoading } = useQuery({
    queryKey: ['policy-version', activeKey, effectiveVersionNumber],
    queryFn: () => policyService.getVersion(activeKey, effectiveVersionNumber!),
    enabled: !!activeKey && effectiveVersionNumber != null,
  })

  // Comparison query: target version
  const { data: compareVersionData } = useQuery({
    queryKey: ['policy-version', activeKey, compareTargetVersion],
    queryFn: () => policyService.getVersion(activeKey, compareTargetVersion!),
    enabled: !!activeKey && compareTargetVersion != null,
  })

  // 3. Fetch Audit History
  const { data: auditHistory, isLoading: auditLoading } = useQuery({
    queryKey: ['policy-audit', activeKey],
    queryFn: () => policyService.getAuditHistory(activeKey),
    enabled: !!activeKey && activeTab === 'audit',
  })

  // Mutations
  const createDefMutation = useMutation({
    mutationFn: policyService.createDefinition,
    onSuccess: (newDef) => {
      showToast('success', 'Policy Created', `Policy '${newDef.policyKey}' registered successfully.`)
      setIsCreateDefinitionOpen(false)
      setNewDefKey('')
      setNewDefName('')
      setNewDefDesc('')
      setSelectedPolicyKey(newDef.policyKey)
      queryClient.invalidateQueries({ queryKey: ['policy-definitions'] })
    },
    onError: (err: Error) => showToast('error', 'Creation Failed', err.message),
  })

  const createVersionMutation = useMutation({
    mutationFn: ({ key, rules, desc }: { key: string; rules: PolicyRule[]; desc?: string }) =>
      policyService.createVersion(key, { description: desc, rules }),
    onSuccess: (newVer) => {
      showToast('success', 'Candidate Version Created', `Version ${newVer.versionNumber} generated in DRAFT state.`)
      setIsCreateVersionOpen(false)
      setNewVersionDesc('')
      setNewVersionRulesJson('')
      setSelectedVersionNumber(newVer.versionNumber)
      queryClient.invalidateQueries({ queryKey: ['policy-versions', activeKey] })
    },
    onError: (err: Error) => showToast('error', 'Version Creation Failed', err.message),
  })

  const activateMutation = useMutation({
    mutationFn: ({ key, ver, reason }: { key: string; ver: number; reason?: string }) =>
      policyService.activateVersion(key, ver, reason),
    onSuccess: (updated) => {
      showToast('success', 'Policy Activated', `Version ${updated.activeVersionNumber} is now live and enforced.`)
      setIsActivateModalOpen(false)
      setActivationReason('')
      queryClient.invalidateQueries({ queryKey: ['policy-definitions'] })
      queryClient.invalidateQueries({ queryKey: ['policy-versions', activeKey] })
      queryClient.invalidateQueries({ queryKey: ['policy-audit', activeKey] })
    },
    onError: (err: Error) => showToast('error', 'Activation Failed', err.message),
  })

  const rollbackMutation = useMutation({
    mutationFn: ({ key, targetVer, reason }: { key: string; targetVer?: number; reason?: string }) =>
      policyService.rollback(key, targetVer, reason),
    onSuccess: (updated) => {
      showToast('warning', 'Policy Rolled Back', `Active version safely reverted to v${updated.activeVersionNumber}.`)
      setIsRollbackModalOpen(false)
      setRollbackReason('')
      setRollbackTargetVersion('')
      queryClient.invalidateQueries({ queryKey: ['policy-definitions'] })
      queryClient.invalidateQueries({ queryKey: ['policy-versions', activeKey] })
      queryClient.invalidateQueries({ queryKey: ['policy-audit', activeKey] })
    },
    onError: (err: Error) => showToast('error', 'Rollback Failed', err.message),
  })

  const shadowMutation = useMutation({
    mutationFn: ({ key, shadowVer }: { key: string; shadowVer: number | null }) =>
      policyService.configureShadow(key, shadowVer),
    onSuccess: (updated) => {
      showToast(
        'success',
        'Shadow Configuration Updated',
        updated.shadowVersionNumber
          ? `Version ${updated.shadowVersionNumber} configured for shadow evaluation.`
          : 'Shadow evaluation disabled.'
      )
      setIsShadowModalOpen(false)
      queryClient.invalidateQueries({ queryKey: ['policy-definitions'] })
    },
    onError: (err: Error) => showToast('error', 'Shadow Configuration Failed', err.message),
  })

  const simulateMutation = useMutation({
    mutationFn: () =>
      policyService.simulate(activeKey, {
        candidateVersionNumber: effectiveVersionNumber || undefined,
        routeId: simRouteId.trim(),
        httpMethod: simHttpMethod.trim().toUpperCase(),
        consumerPlan: simConsumerPlan.trim().toUpperCase(),
        apiKeyScopes: simScopes
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
        clientIp: simClientIp.trim(),
      }),
    onSuccess: (res) => {
      setSimResult(res)
      showToast('info', 'Simulation Evaluated', `Decision: ${res.decision} in ${(res.durationNanos / 1000).toFixed(1)} µs`)
    },
    onError: (err: Error) => showToast('error', 'Simulation Failed', err.message),
  })

  const replayMutation = useMutation({
    mutationFn: () =>
      policyService.replay(activeKey, {
        candidateVersionNumber: effectiveVersionNumber || undefined,
        sampleLimit: replaySampleLimit,
        sinceHours: replaySinceHours,
      }),
    onSuccess: (res) => {
      setReplayResult(res)
      showToast('info', 'Replay Complete', `Sampled ${res.totalRecordsSampled} telemetry events.`)
    },
    onError: (err: Error) => showToast('error', 'Replay Failed', err.message),
  })

  // Create Version Form Helpers
  const handleOpenCreateVersion = () => {
    // Pre-populate with current rules if available
    if (currentVersion?.rules) {
      setNewVersionRulesJson(JSON.stringify(currentVersion.rules, null, 2))
    } else {
      const templateRule: PolicyRule[] = [
        {
          ruleId: 'allow-standard-ingress',
          priority: 100,
          enabled: true,
          conditions: {
            routeId: '/api/v1/orders',
            httpMethod: 'POST',
            consumerPlan: 'ENTERPRISE',
            requiredScopes: ['gateway:invoke'],
          },
          action: 'ALLOW_CONTINUE',
          description: 'Allows enterprise post orders ingress',
          reasonCode: 'POLICY_PERMITTED',
          safeReason: 'Valid ingress rule satisfied',
        },
      ]
      setNewVersionRulesJson(JSON.stringify(templateRule, null, 2))
    }
    setJsonValidationError(null)
    setIsCreateVersionOpen(true)
  }

  const handleCreateVersionSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    try {
      const parsed = JSON.parse(newVersionRulesJson)
      if (!Array.isArray(parsed) || parsed.length === 0) {
        setJsonValidationError('Rules must be a non-empty JSON array of PolicyRule objects.')
        return
      }
      createVersionMutation.mutate({
        key: activeKey,
        rules: parsed,
        desc: newVersionDesc.trim() || undefined,
      })
    } catch (err: unknown) {
      setJsonValidationError(err instanceof Error ? err.message : 'Invalid JSON format')
    }
  }

  return (
    <div className="space-y-6">
      {/* 1. Header Toolbar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-moss/40">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-bold tracking-tight text-silver-frost">
              Policy Studio & Rule Engine
            </h1>
            <Badge variant="slate" className="border-moss text-titanium text-[10px]">
              v5C Deterministic Kernel
            </Badge>
          </div>
          <p className="text-xs text-titanium mt-1">
            Versioned, explainable ingress authorization rules with dry-run simulation and historical telemetry replay
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              refetchDefs()
              refetchVersions()
            }}
            disabled={isFetchingDefs}
            className="text-xs border-moss/60 text-titanium hover:text-silver-frost bg-surface"
          >
            <RefreshCw className={`w-3.5 h-3.5 mr-1.5 ${isFetchingDefs ? 'animate-spin text-emerald' : ''}`} />
            Refresh
          </Button>

          {canMutate() && (
            <Button
              variant="primary"
              size="sm"
              onClick={() => setIsCreateDefinitionOpen(true)}
              className="text-xs"
            >
              <Plus className="w-3.5 h-3.5 mr-1" />
              New Policy Container
            </Button>
          )}
        </div>
      </div>

      {/* 2. Policy Route Selector & Metadata Overview Bar */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
        {/* Selector Dropdown */}
        <Card className="bg-surface border-moss/40 p-4 flex flex-col justify-between">
          <div>
            <label className="block text-xs font-semibold text-titanium mb-1.5">
              Select Policy Definition
            </label>
            {defsLoading ? (
              <Skeleton className="h-9 w-full" />
            ) : !defsList || defsList.length === 0 ? (
              <div className="text-xs text-titanium-muted italic">No policy definitions found.</div>
            ) : (
              <select
                value={activeKey}
                onChange={(e) => {
                  setSelectedPolicyKey(e.target.value)
                  setSelectedVersionNumber(null)
                  setSimResult(null)
                  setReplayResult(null)
                }}
                className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-medium focus:outline-none focus:border-emerald"
              >
                {defsList.map((def) => (
                  <option key={def.id} value={def.policyKey}>
                    {def.name} ({def.policyKey})
                  </option>
                ))}
              </select>
            )}
          </div>
          {currentDef && (
            <div className="text-[11px] text-titanium mt-3 line-clamp-2">
              {currentDef.description || 'No description provided.'}
            </div>
          )}
        </Card>

        {/* Active Version Card */}
        <Card className="bg-surface border-moss/40 p-4">
          <div className="flex items-center justify-between text-xs text-titanium">
            <span className="font-medium">Active Policy Version</span>
            <Lock className="w-3.5 h-3.5 text-emerald" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-2xl font-bold font-mono text-emerald">
              {currentDef?.activeVersionNumber != null ? `v${currentDef.activeVersionNumber}` : 'None'}
            </span>
            {currentDef?.activeVersionNumber != null && (
              <Badge variant="success" className="text-[10px]">
                Enforcing Live
              </Badge>
            )}
          </div>
          <div className="mt-2 text-[11px] text-titanium-muted flex items-center gap-1 font-mono">
            <span>Lock: #{currentDef?.versionLock ?? 0}</span>
            <span>•</span>
            <span>{currentDef?.updatedAt ? formatDate(currentDef.updatedAt) : '—'}</span>
          </div>
        </Card>

        {/* Shadow Version Card */}
        <Card className="bg-surface border-moss/40 p-4">
          <div className="flex items-center justify-between text-xs text-titanium">
            <span className="font-medium">Shadow Evaluation</span>
            <Eye className="w-3.5 h-3.5 text-titanium-muted" />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-2xl font-bold font-mono text-silver-frost">
              {currentDef?.shadowVersionNumber != null ? `v${currentDef.shadowVersionNumber}` : 'Disabled'}
            </span>
            {currentDef?.shadowVersionNumber != null && (
              <Badge variant="slate" className="border-moss text-titanium text-[10px]">
                Passive
              </Badge>
            )}
          </div>
          <div className="mt-2">
            {canMutate() && (
              <button
                onClick={() => {
                  setShadowTargetVersion(
                    currentDef?.shadowVersionNumber != null ? String(currentDef.shadowVersionNumber) : ''
                  )
                  setIsShadowModalOpen(true)
                }}
                className="text-[11px] text-emerald hover:text-emerald/80 underline font-medium"
              >
                Configure Shadow Mode &rarr;
              </button>
            )}
          </div>
        </Card>

        {/* Actions & Lifecycle Governance */}
        <Card className="bg-surface border-moss/40 p-4 flex flex-col justify-between">
          <span className="text-xs font-semibold text-titanium">Version Governance</span>
          <div className="flex flex-wrap gap-2 mt-2">
            {canMutate() && (
              <>
                <Button
                  variant="primary"
                  size="sm"
                  onClick={handleOpenCreateVersion}
                  className="text-[11px] py-1 px-2.5"
                >
                  <Plus className="w-3 h-3 mr-1" />
                  New Version
                </Button>

                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setIsRollbackModalOpen(true)}
                  className="text-[11px] py-1 px-2.5 border-amber/40 text-amber hover:bg-amber/10"
                >
                  <RotateCcw className="w-3 h-3 mr-1" />
                  Rollback
                </Button>
              </>
            )}
          </div>
          <span className="text-[10px] text-titanium-muted mt-2">
            All created versions are strictly immutable documents.
          </span>
        </Card>
      </div>

      {/* 3. Version History Ribbon */}
      {versionsList && versionsList.length > 0 && (
        <div className="flex items-center gap-2 overflow-x-auto pb-2 scrollbar-none">
          <span className="text-xs font-medium text-titanium shrink-0 flex items-center gap-1">
            <History className="w-3.5 h-3.5" />
            Versions:
          </span>
          {versionsList.map((ver) => {
            const isSelected = effectiveVersionNumber === ver.versionNumber
            const isActive = currentDef?.activeVersionNumber === ver.versionNumber
            const isShadow = currentDef?.shadowVersionNumber === ver.versionNumber

            return (
              <button
                key={ver.id}
                onClick={() => {
                  setSelectedVersionNumber(ver.versionNumber)
                  setSimResult(null)
                }}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-mono transition-all border shrink-0 ${
                  isSelected
                    ? 'bg-surface-elevated text-silver-frost border-emerald shadow-emerald-sm'
                    : 'bg-surface text-titanium border-moss/40 hover:border-moss'
                }`}
              >
                <span className="font-bold">v{ver.versionNumber}</span>
                {isActive && (
                  <span className="px-1 py-0.2 text-[9px] bg-emerald/20 text-emerald font-sans font-bold rounded">
                    ACTIVE
                  </span>
                )}
                {isShadow && (
                  <span className="px-1 py-0.2 text-[9px] bg-amber/20 text-amber font-sans font-bold rounded">
                    SHADOW
                  </span>
                )}
                <span className="text-titanium-muted text-[10px]">({ver.rulesCount} rules)</span>
              </button>
            )
          })}
        </div>
      )}

      {/* 4. Tab Navigation */}
      <div className="border-b border-moss/40 flex items-center gap-6 text-xs">
        <button
          onClick={() => setActiveTab('rules')}
          className={`pb-3 font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'rules'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <ListOrdered className="w-4 h-4" />
          Rule Inspector (v{effectiveVersionNumber ?? '—'})
        </button>

        <button
          onClick={() => setActiveTab('simulation')}
          className={`pb-3 font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'simulation'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <Play className="w-4 h-4" />
          Dry-Run Simulation
        </button>

        <button
          onClick={() => setActiveTab('replay')}
          className={`pb-3 font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'replay'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <History className="w-4 h-4" />
          Historical Replay
        </button>

        <button
          onClick={() => setActiveTab('compare')}
          className={`pb-3 font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'compare'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <GitCompare className="w-4 h-4" />
          Version Diff
        </button>

        <button
          onClick={() => setActiveTab('audit')}
          className={`pb-3 font-semibold flex items-center gap-2 border-b-2 transition-colors ${
            activeTab === 'audit'
              ? 'border-emerald text-emerald'
              : 'border-transparent text-titanium hover:text-silver-frost'
          }`}
        >
          <ShieldCheck className="w-4 h-4" />
          Governance Audit Trail
        </button>
      </div>

      {/* TAB CONTENT */}

      {/* TAB 1: Rule Inspector */}
      {activeTab === 'rules' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h3 className="text-sm font-semibold text-silver-frost">
                Evaluated Rules in Version {effectiveVersionNumber}
              </h3>
              {currentVersion?.status && (
                <Badge
                  variant={currentVersion.status === 'ACTIVE' ? 'success' : 'slate'}
                  className="text-[10px]"
                >
                  {currentVersion.status}
                </Badge>
              )}
            </div>

            {canMutate() && currentDef?.activeVersionNumber !== effectiveVersionNumber && effectiveVersionNumber != null && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => {
                  setActivateTargetVersion(effectiveVersionNumber)
                  setIsActivateModalOpen(true)
                }}
                className="text-xs"
              >
                <Zap className="w-3.5 h-3.5 mr-1 text-emerald" />
                Activate Version {effectiveVersionNumber}
              </Button>
            )}
          </div>

          {currentVersionLoading ? (
            <div className="space-y-3">
              <Skeleton className="h-20 w-full" />
              <Skeleton className="h-20 w-full" />
            </div>
          ) : !currentVersion || currentVersion.rules.length === 0 ? (
            <Card className="p-12 text-center bg-surface border-moss/40">
              <p className="text-sm font-medium text-silver-frost">No rules defined in this version.</p>
              <p className="text-xs text-titanium mt-1">Create a candidate version to add rule definitions.</p>
            </Card>
          ) : (
            <div className="space-y-3">
              {currentVersion.rules.map((rule, idx) => {
                const isAllow = rule.action === 'ALLOW_CONTINUE'

                return (
                  <Card
                    key={rule.ruleId}
                    className="bg-surface border-moss/40 p-4 hover:border-moss transition-colors"
                  >
                    <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
                      {/* Rule Identity & Order */}
                      <div className="flex items-start gap-3">
                        <div className="w-8 h-8 rounded-lg bg-surface-elevated border border-moss/50 flex items-center justify-center font-mono font-bold text-xs text-emerald shrink-0">
                          #{idx + 1}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-semibold text-silver-frost text-sm">{rule.ruleId}</span>
                            <Badge
                              variant={isAllow ? 'success' : 'danger'}
                              className="font-mono text-[10px]"
                            >
                              {rule.action}
                            </Badge>
                            {!rule.enabled && <Badge variant="slate">DISABLED</Badge>}
                          </div>
                          <p className="text-xs text-titanium mt-0.5">
                            {rule.description || 'No description provided.'}
                          </p>
                        </div>
                      </div>

                      {/* Rule Priority Badge */}
                      <div className="flex items-center gap-2 text-xs">
                        <span className="text-titanium font-medium">Priority Precedence:</span>
                        <span className="font-mono font-bold text-silver-frost bg-surface-elevated px-2 py-0.5 rounded border border-moss/40">
                          {rule.priority}
                        </span>
                      </div>
                    </div>

                    {/* Conditions Matrix */}
                    <div className="mt-3 pt-3 border-t border-moss/20 grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
                      <div>
                        <span className="text-[10px] text-titanium uppercase tracking-wider block">
                          Route Pattern
                        </span>
                        <span className="font-mono text-silver-frost">
                          {rule.conditions?.routeId || 'Any (*)'}
                        </span>
                      </div>
                      <div>
                        <span className="text-[10px] text-titanium uppercase tracking-wider block">
                          HTTP Method
                        </span>
                        <span className="font-mono text-silver-frost">
                          {rule.conditions?.httpMethod || 'Any (*)'}
                        </span>
                      </div>
                      <div>
                        <span className="text-[10px] text-titanium uppercase tracking-wider block">
                          Consumer Plan
                        </span>
                        <span className="font-mono text-silver-frost">
                          {rule.conditions?.consumerPlan || 'Any (*)'}
                        </span>
                      </div>
                      <div>
                        <span className="text-[10px] text-titanium uppercase tracking-wider block">
                          Time Window (UTC)
                        </span>
                        <span className="font-mono text-silver-frost">
                          {rule.conditions?.timeWindowStartUtc && rule.conditions?.timeWindowEndUtc
                            ? `${rule.conditions.timeWindowStartUtc} - ${rule.conditions.timeWindowEndUtc}`
                            : 'All Hours'}
                        </span>
                      </div>
                    </div>

                    {/* Scopes & Reason Code */}
                    {(rule.conditions?.requiredScopes || rule.reasonCode) && (
                      <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2 text-[11px]">
                        <div className="flex items-center gap-1.5">
                          <span className="text-titanium">Required Scopes:</span>
                          {rule.conditions?.requiredScopes && rule.conditions.requiredScopes.length > 0 ? (
                            rule.conditions.requiredScopes.map((sc) => (
                              <span
                                key={sc}
                                className="px-1.5 py-0.5 bg-surface-elevated border border-moss/30 rounded font-mono text-[10px] text-silver-frost"
                              >
                                {sc}
                              </span>
                            ))
                          ) : (
                            <span className="text-titanium-muted italic">None</span>
                          )}
                        </div>

                        <div className="text-titanium font-mono text-[10px]">
                          Reason: <span className="text-silver-frost">{rule.reasonCode}</span> (
                          {rule.safeReason})
                        </div>
                      </div>
                    )}
                  </Card>
                )
              })}
            </div>
          )}
        </div>
      )}

      {/* TAB 2: Dry-Run Simulation */}
      {activeTab === 'simulation' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Simulation Request Form */}
          <Card className="bg-surface border-moss/40 p-5 space-y-4">
            <div className="flex items-center justify-between border-b border-moss/30 pb-3">
              <CardTitle className="text-sm font-semibold text-silver-frost flex items-center gap-2">
                <Play className="w-4 h-4 text-emerald" />
                Configure Simulation Probe
              </CardTitle>
              <Badge variant="slate" className="border-moss text-titanium text-[10px]">
                Target: v{effectiveVersionNumber}
              </Badge>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block text-xs font-semibold text-titanium mb-1">Target Route ID / Path</label>
                <input
                  type="text"
                  value={simRouteId}
                  onChange={(e) => setSimRouteId(e.target.value)}
                  placeholder="/api/v1/orders"
                  className="w-full px-3 py-1.5 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-titanium mb-1">HTTP Method</label>
                <select
                  value={simHttpMethod}
                  onChange={(e) => setSimHttpMethod(e.target.value)}
                  className="w-full px-3 py-1.5 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
                >
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold text-titanium mb-1">Consumer Plan Tier</label>
                <select
                  value={simConsumerPlan}
                  onChange={(e) => setSimConsumerPlan(e.target.value)}
                  className="w-full px-3 py-1.5 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
                >
                  <option value="STARTER">STARTER</option>
                  <option value="GROWTH">GROWTH</option>
                  <option value="ENTERPRISE">ENTERPRISE</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold text-titanium mb-1">
                  API Key Scopes (comma-separated)
                </label>
                <input
                  type="text"
                  value={simScopes}
                  onChange={(e) => setSimScopes(e.target.value)}
                  placeholder="gateway:invoke, read:orders"
                  className="w-full px-3 py-1.5 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-titanium mb-1">Client Ingress IP</label>
                <input
                  type="text"
                  value={simClientIp}
                  onChange={(e) => setSimClientIp(e.target.value)}
                  placeholder="192.168.1.100"
                  className="w-full px-3 py-1.5 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
                />
              </div>
            </div>

            <Button
              variant="primary"
              onClick={() => simulateMutation.mutate()}
              disabled={simulateMutation.isPending || !activeKey}
              className="w-full text-xs"
            >
              <Play className="w-3.5 h-3.5 mr-1.5" />
              {simulateMutation.isPending ? 'Simulating...' : 'Execute Dry-Run Simulation'}
            </Button>

            <div className="p-3 bg-surface-elevated border border-moss/30 rounded-lg text-[11px] text-titanium-muted flex items-start gap-2">
              <Sparkles className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
              <span>
                Zero runtime side effects: Redis token quotas are not deducted and upstream services are never called.
              </span>
            </div>
          </Card>

          {/* Simulation Decision & Trace Display */}
          <div className="lg:col-span-2 space-y-4">
            {simResult ? (
              <Card className="bg-surface border-moss/40 overflow-hidden">
                <CardHeader className="border-b border-moss/30 bg-surface-elevated/40 pb-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <span className="text-xs text-titanium font-medium">Evaluation Decision</span>
                      <div className="flex items-center gap-3 mt-1">
                        <span
                          className={`text-2xl font-bold font-mono ${
                            simResult.decision === 'ALLOW_CONTINUE' ? 'text-emerald' : 'text-crimson'
                          }`}
                        >
                          {simResult.decision}
                        </span>
                        <Badge
                          variant={simResult.decision === 'ALLOW_CONTINUE' ? 'success' : 'danger'}
                          className="font-mono text-xs"
                        >
                          {simResult.reasonCode}
                        </Badge>
                      </div>
                    </div>

                    <div className="text-right text-xs">
                      <span className="text-titanium block">Kernel Latency</span>
                      <span className="font-mono font-bold text-silver-frost">
                        {(simResult.durationNanos / 1000).toFixed(1)} µs
                      </span>
                    </div>
                  </div>
                  <p className="text-xs text-titanium mt-2 italic">{simResult.safeReason}</p>
                </CardHeader>

                <CardContent className="p-5 space-y-4">
                  <h4 className="text-xs font-semibold text-silver-frost uppercase tracking-wider">
                    Sequential Rule Evaluation Trace
                  </h4>

                  {simResult.trace?.ruleEvaluations && simResult.trace.ruleEvaluations.length > 0 ? (
                    <div className="divide-y divide-moss/20 border border-moss/30 rounded-lg overflow-hidden text-xs">
                      {simResult.trace.ruleEvaluations.map((traceRule) => (
                        <div
                          key={traceRule.ruleId}
                          className={`p-3 flex items-start justify-between gap-4 transition-colors ${
                            traceRule.matched ? 'bg-surface-elevated/80' : 'bg-surface/50'
                          }`}
                        >
                          <div className="flex items-start gap-2.5">
                            {traceRule.matched ? (
                              <CheckCircle2 className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
                            ) : (
                              <XCircle className="w-4 h-4 text-titanium-muted shrink-0 mt-0.5" />
                            )}
                            <div>
                              <div className="flex items-center gap-2">
                                <span className="font-semibold text-silver-frost font-mono">
                                  {traceRule.ruleId}
                                </span>
                                <span className="text-[10px] font-mono text-titanium">
                                  (priority {traceRule.priority})
                                </span>
                                <Badge
                                  variant={traceRule.action === 'ALLOW_CONTINUE' ? 'success' : 'danger'}
                                  className="text-[9px] py-0"
                                >
                                  {traceRule.action}
                                </Badge>
                              </div>
                              <div className="text-[11px] text-titanium mt-1">
                                {traceRule.matched
                                  ? traceRule.matchExplanation || 'All conditions satisfied.'
                                  : traceRule.nonMatchReason || 'Did not match rule criteria.'}
                              </div>
                            </div>
                          </div>

                          <div className="text-right shrink-0">
                            {traceRule.matched ? (
                              <span className="px-2 py-0.5 bg-emerald/15 text-emerald text-[10px] font-bold rounded font-mono">
                                MATCHED
                              </span>
                            ) : (
                              <span className="text-titanium-muted text-[10px] font-mono">SKIPPED</span>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="p-4 text-center text-xs text-titanium-muted">
                      No individual rule trace items returned.
                    </div>
                  )}
                </CardContent>
              </Card>
            ) : (
              <Card className="p-12 text-center bg-surface border-moss/40">
                <Play className="w-8 h-8 text-titanium-muted/40 mx-auto mb-2" />
                <p className="text-sm font-medium text-silver-frost">No simulation run yet</p>
                <p className="text-xs text-titanium mt-1">
                  Configure mock ingress parameters on the left and click "Execute Dry-Run Simulation".
                </p>
              </Card>
            )}
          </div>
        </div>
      )}

      {/* TAB 3: Historical Replay Impact Analysis */}
      {activeTab === 'replay' && (
        <div className="space-y-6">
          <Card className="bg-surface border-moss/40 p-5 space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-moss/30 pb-3">
              <div>
                <CardTitle className="text-sm font-semibold text-silver-frost flex items-center gap-2">
                  <History className="w-4 h-4 text-emerald" />
                  Historical Traffic Replay Impact Analysis
                </CardTitle>
                <CardDescription className="text-xs text-titanium mt-0.5">
                  Replays candidate policy v{effectiveVersionNumber} against genuine logged telemetry events to detect unexpected regressions
                </CardDescription>
              </div>

              <div className="flex items-center gap-3">
                <div className="flex items-center gap-2 text-xs text-titanium">
                  <span>Sample Limit:</span>
                  <input
                    type="number"
                    min={1}
                    max={500}
                    value={replaySampleLimit}
                    onChange={(e) => setReplaySampleLimit(Number(e.target.value))}
                    className="w-16 px-2 py-1 bg-surface-elevated border border-moss/40 rounded text-silver-frost font-mono text-xs"
                  />
                </div>

                <div className="flex items-center gap-2 text-xs text-titanium">
                  <span>Lookback Hours:</span>
                  <input
                    type="number"
                    min={1}
                    max={720}
                    value={replaySinceHours}
                    onChange={(e) => setReplaySinceHours(Number(e.target.value))}
                    className="w-16 px-2 py-1 bg-surface-elevated border border-moss/40 rounded text-silver-frost font-mono text-xs"
                  />
                </div>

                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => replayMutation.mutate()}
                  disabled={replayMutation.isPending || !activeKey}
                  className="text-xs"
                >
                  {replayMutation.isPending ? 'Replaying...' : 'Run Historical Replay'}
                </Button>
              </div>
            </div>
          </Card>

          {replayResult && (
            <div className="space-y-4">
              {/* Replay Summary Cards */}
              <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
                <Card className="bg-surface border-moss/40 p-3">
                  <span className="text-[10px] text-titanium uppercase">Sampled Records</span>
                  <div className="text-lg font-bold font-mono text-silver-frost mt-0.5">
                    {replayResult.totalRecordsSampled}
                  </div>
                </Card>
                <Card className="bg-surface border-moss/40 p-3">
                  <span className="text-[10px] text-titanium uppercase">Decisions Unchanged</span>
                  <div className="text-lg font-bold font-mono text-emerald mt-0.5">
                    {replayResult.decisionsUnchanged}
                  </div>
                </Card>
                <Card className="bg-surface border-moss/40 p-3">
                  <span className="text-[10px] text-titanium uppercase">Newly Denied</span>
                  <div className="text-lg font-bold font-mono text-crimson mt-0.5">
                    {replayResult.newlyDenied}
                  </div>
                </Card>
                <Card className="bg-surface border-moss/40 p-3">
                  <span className="text-[10px] text-titanium uppercase">Newly Permitted</span>
                  <div className="text-lg font-bold font-mono text-emerald mt-0.5">
                    {replayResult.newlyPermitted}
                  </div>
                </Card>
                <Card className="bg-surface border-moss/40 p-3">
                  <span className="text-[10px] text-titanium uppercase">Not Evaluable</span>
                  <div className="text-lg font-bold font-mono text-titanium-muted mt-0.5">
                    {replayResult.notEvaluableCount}
                  </div>
                </Card>
              </div>

              {/* Differences List */}
              {replayResult.sampleDifferences && replayResult.sampleDifferences.length > 0 ? (
                <Card className="bg-surface border-moss/40 overflow-hidden">
                  <CardHeader className="border-b border-moss/30 bg-surface-elevated/40 pb-3">
                    <CardTitle className="text-xs font-semibold text-silver-frost">
                      Decision Outcome Discrepancies
                    </CardTitle>
                  </CardHeader>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-xs border-collapse">
                      <thead className="bg-surface-elevated text-titanium uppercase text-[10px]">
                        <tr>
                          <th className="py-2.5 px-4">Endpoint</th>
                          <th className="py-2.5 px-4">Method</th>
                          <th className="py-2.5 px-4">Plan</th>
                          <th className="py-2.5 px-4">Active Decision</th>
                          <th className="py-2.5 px-4">Candidate Decision</th>
                          <th className="py-2.5 px-4">Explanation</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-moss/20 text-silver-frost">
                        {replayResult.sampleDifferences.map((diff, i) => (
                          <tr key={i} className="hover:bg-surface-elevated/40">
                            <td className="py-2.5 px-4 font-mono">{diff.endpointPath}</td>
                            <td className="py-2.5 px-4 font-bold">{diff.httpMethod}</td>
                            <td className="py-2.5 px-4">{diff.consumerPlan}</td>
                            <td className="py-2.5 px-4 font-mono text-titanium">{diff.activeDecision}</td>
                            <td className="py-2.5 px-4 font-mono font-bold text-silver-frost">
                              {diff.candidateDecision}
                            </td>
                            <td className="py-2.5 px-4 text-titanium">{diff.differenceReason}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>
              ) : (
                <div className="p-6 bg-surface border border-moss/30 rounded-xl text-center text-xs text-titanium">
                  No decision differences detected across sampled historical events.
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* TAB 4: Version Comparison */}
      {activeTab === 'compare' && (
        <div className="space-y-4">
          <Card className="bg-surface border-moss/40 p-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
              <div>
                <CardTitle className="text-sm font-semibold text-silver-frost">
                  Compare Active Version with Candidate Version
                </CardTitle>
                <CardDescription className="text-xs text-titanium">
                  Examine rule differences between active version (v{currentDef?.activeVersionNumber ?? 'None'}) and selected version
                </CardDescription>
              </div>

              <div className="flex items-center gap-2">
                <span className="text-xs text-titanium">Compare with:</span>
                <select
                  value={compareTargetVersion ?? ''}
                  onChange={(e) => setCompareTargetVersion(e.target.value ? Number(e.target.value) : null)}
                  className="px-2.5 py-1 text-xs bg-surface-elevated border border-moss/40 rounded text-silver-frost font-mono"
                >
                  <option value="">Select version...</option>
                  {versions?.map((v) => (
                    <option key={v.id} value={v.versionNumber}>
                      v{v.versionNumber} ({v.status})
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </Card>

          {compareTargetVersion != null && compareVersionData && currentDef?.activeVersionNumber != null ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <Card className="bg-surface border-moss/40 p-4">
                <h4 className="text-xs font-semibold text-emerald uppercase tracking-wider mb-2">
                  Active (v{currentDef.activeVersionNumber}) Rules
                </h4>
                <div className="space-y-2">
                  {currentVersion?.rules.map((r) => (
                    <div key={r.ruleId} className="p-2.5 bg-surface-elevated rounded border border-moss/30 text-xs">
                      <div className="flex items-center justify-between">
                        <span className="font-semibold text-silver-frost font-mono">{r.ruleId}</span>
                        <Badge variant={r.action === 'ALLOW_CONTINUE' ? 'success' : 'danger'} className="text-[9px]">
                          {r.action}
                        </Badge>
                      </div>
                      <div className="text-[11px] text-titanium mt-1">Priority: {r.priority}</div>
                    </div>
                  ))}
                </div>
              </Card>

              <Card className="bg-surface border-moss/40 p-4">
                <h4 className="text-xs font-semibold text-silver-frost uppercase tracking-wider mb-2">
                  Candidate (v{compareTargetVersion}) Rules
                </h4>
                <div className="space-y-2">
                  {compareVersionData.rules.map((r) => (
                    <div key={r.ruleId} className="p-2.5 bg-surface-elevated rounded border border-moss/30 text-xs">
                      <div className="flex items-center justify-between">
                        <span className="font-semibold text-silver-frost font-mono">{r.ruleId}</span>
                        <Badge variant={r.action === 'ALLOW_CONTINUE' ? 'success' : 'danger'} className="text-[9px]">
                          {r.action}
                        </Badge>
                      </div>
                      <div className="text-[11px] text-titanium mt-1">Priority: {r.priority}</div>
                    </div>
                  ))}
                </div>
              </Card>
            </div>
          ) : (
            <div className="p-12 text-center text-xs text-titanium">
              Select a version above to view rule-by-rule comparisons.
            </div>
          )}
        </div>
      )}

      {/* TAB 5: Governance & Audit Trail */}
      {activeTab === 'audit' && (
        <Card className="bg-surface border-moss/40 overflow-hidden">
          <CardHeader className="border-b border-moss/30 bg-surface-elevated/40 pb-3">
            <CardTitle className="text-sm font-semibold text-silver-frost">
              Policy Activation & Governance Audit History
            </CardTitle>
            <CardDescription className="text-xs text-titanium">
              Immutable chronological record of policy promotions, rollbacks, and shadow configurations
            </CardDescription>
          </CardHeader>
          <CardContent className="p-0">
            {auditLoading ? (
              <div className="p-6 space-y-3">
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-full" />
              </div>
            ) : !auditHistory || auditHistory.length === 0 ? (
              <div className="p-12 text-center text-titanium-muted">
                <ShieldCheck className="w-8 h-8 text-titanium-muted/40 mx-auto mb-2" />
                <p className="text-sm font-semibold text-silver-frost">No audit events recorded</p>
                <p className="text-xs text-titanium mt-1">
                  Actions such as activations and rollbacks will appear here.
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs border-collapse">
                  <thead className="bg-surface-elevated/70 border-b border-moss/30 text-titanium uppercase tracking-wider font-medium text-[11px]">
                    <tr>
                      <th className="px-5 py-3">Timestamp (UTC)</th>
                      <th className="px-5 py-3">Action</th>
                      <th className="px-5 py-3">Version</th>
                      <th className="px-5 py-3">Previous Version</th>
                      <th className="px-5 py-3">Authorized Actor</th>
                      <th className="px-5 py-3">Audit Reason</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-moss/20 text-silver-frost">
                    {auditHistory.map((audit) => (
                      <tr key={audit.id} className="hover:bg-surface-elevated/40">
                        <td className="px-5 py-3 text-titanium font-mono text-[11px]">
                          {formatDate(audit.createdAt)}
                        </td>
                        <td className="px-5 py-3 font-semibold font-mono">
                          <span
                            className={
                              audit.action === 'ACTIVATED'
                                ? 'text-emerald'
                                : audit.action === 'ROLLED_BACK'
                                ? 'text-amber'
                                : 'text-titanium'
                            }
                          >
                            {audit.action}
                          </span>
                        </td>
                        <td className="px-5 py-3 font-mono font-bold">v{audit.versionNumber}</td>
                        <td className="px-5 py-3 font-mono text-titanium">
                          {audit.previousVersionNumber != null ? `v${audit.previousVersionNumber}` : '—'}
                        </td>
                        <td className="px-5 py-3 font-mono">{audit.actor}</td>
                        <td className="px-5 py-3 text-titanium italic">{audit.reason || 'No reason provided.'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* 5. MODALS */}

      {/* Modal: Create Policy Definition */}
      <Modal
        isOpen={isCreateDefinitionOpen}
        onClose={() => setIsCreateDefinitionOpen(false)}
        title="Register New Policy Container"
        description="Creates a versioned policy namespace linked to gateway routes."
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            createDefMutation.mutate({
              policyKey: newDefKey.trim(),
              name: newDefName.trim(),
              description: newDefDesc.trim() || undefined,
            })
          }}
          className="space-y-4"
        >
          <Input
            label="Policy Key (3-64 chars alphanumeric, hyphens, underscores)"
            placeholder="e.g. orders-policy"
            value={newDefKey}
            onChange={(e) => setNewDefKey(e.target.value)}
            pattern="^[a-zA-Z0-9_-]{3,64}$"
            required
          />

          <Input
            label="Policy Friendly Name"
            placeholder="e.g. Orders Ingress Policy"
            value={newDefName}
            onChange={(e) => setNewDefName(e.target.value)}
            required
          />

          <Input
            label="Description (Optional)"
            placeholder="Enforces rate limits, role scopes, and operational hours..."
            value={newDefDesc}
            onChange={(e) => setNewDefDesc(e.target.value)}
          />

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" type="button" onClick={() => setIsCreateDefinitionOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={createDefMutation.isPending}>
              {createDefMutation.isPending ? 'Creating...' : 'Create Policy'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Create Immutable Version */}
      <Modal
        isOpen={isCreateVersionOpen}
        onClose={() => setIsCreateVersionOpen(false)}
        title={`Create Candidate Version for '${activeKey}'`}
        description="Candidate versions are saved in DRAFT status and must be explicitly activated."
      >
        <form onSubmit={handleCreateVersionSubmit} className="space-y-4">
          <Input
            label="Version Description (Optional)"
            placeholder="e.g. Added time window restriction for starter plan"
            value={newVersionDesc}
            onChange={(e) => setNewVersionDesc(e.target.value)}
          />

          <div>
            <label className="block text-xs font-semibold text-titanium mb-1">
              Policy Rules JSON (Strict Schema)
            </label>
            <textarea
              rows={12}
              value={newVersionRulesJson}
              onChange={(e) => {
                setNewVersionRulesJson(e.target.value)
                setJsonValidationError(null)
              }}
              className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost font-mono focus:outline-none focus:border-emerald"
              required
            />
            {jsonValidationError && (
              <p className="text-xs text-crimson mt-1">{jsonValidationError}</p>
            )}
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" type="button" onClick={() => setIsCreateVersionOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={createVersionMutation.isPending}>
              {createVersionMutation.isPending ? 'Validating & Creating...' : 'Save Candidate Version'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Activate Version Confirmation */}
      {activateTargetVersion != null && (
        <Modal
          isOpen={isActivateModalOpen}
          onClose={() => setIsActivateModalOpen(false)}
          title={`Confirm Policy Activation: Version ${activateTargetVersion}`}
          description="Promotes candidate version to active enforcement across all gateway nodes."
        >
          <div className="space-y-4">
            <div className="p-3.5 bg-emerald/10 border border-emerald/30 rounded-lg text-xs text-emerald leading-relaxed">
              <strong>Impact Notice:</strong> Version <strong>v{activateTargetVersion}</strong> will become the active
              enforcement policy for route namespace <code>{activeKey}</code>. Current active version (
              <code>v{currentDef?.activeVersionNumber ?? 'None'}</code>) will be retired. Gateway caches will be
              atomically invalidated.
            </div>

            <Input
              label="Activation Audit Reason (Recommended)"
              placeholder="e.g. Approved during sprint release, passed staging simulation"
              value={activationReason}
              onChange={(e) => setActivationReason(e.target.value)}
            />

            <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
              <Button variant="outline" onClick={() => setIsActivateModalOpen(false)}>
                Cancel
              </Button>
              <Button
                variant="primary"
                onClick={() =>
                  activateMutation.mutate({
                    key: activeKey,
                    ver: activateTargetVersion,
                    reason: activationReason.trim() || undefined,
                  })
                }
                disabled={activateMutation.isPending}
              >
                {activateMutation.isPending ? 'Activating...' : 'Confirm Activation'}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Modal: Rollback Confirmation */}
      <Modal
        isOpen={isRollbackModalOpen}
        onClose={() => setIsRollbackModalOpen(false)}
        title={`Rollback Active Policy: '${activeKey}'`}
        description="Reverts active policy to a previously verified immutable version."
      >
        <div className="space-y-4">
          <div className="p-3.5 bg-amber/10 border border-amber/30 rounded-lg text-xs text-amber leading-relaxed">
            <AlertTriangle className="w-4 h-4 inline-block mr-1.5 mb-0.5" />
            <strong>Safety Gate:</strong> Rollback will retire the active version (v{currentDef?.activeVersionNumber})
            and reactivate a prior version without deleting history.
          </div>

          <div>
            <label className="block text-xs font-semibold text-titanium mb-1">
              Target Version (Optional - leave blank for previous active)
            </label>
            <select
              value={rollbackTargetVersion}
              onChange={(e) => setRollbackTargetVersion(e.target.value)}
              className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-amber"
            >
              <option value="">Auto-detect previous active version from audit</option>
              {versions
                ?.filter((v) => v.versionNumber !== currentDef?.activeVersionNumber)
                .map((v) => (
                  <option key={v.id} value={v.versionNumber}>
                    Version v{v.versionNumber} ({v.status})
                  </option>
                ))}
            </select>
          </div>

          <Input
            label="Rollback Audit Reason (Required)"
            placeholder="e.g. False positives detected in production orders"
            value={rollbackReason}
            onChange={(e) => setRollbackReason(e.target.value)}
            required
          />

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" onClick={() => setIsRollbackModalOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="outline"
              onClick={() =>
                rollbackMutation.mutate({
                  key: activeKey,
                  targetVer: rollbackTargetVersion ? Number(rollbackTargetVersion) : undefined,
                  reason: rollbackReason.trim(),
                })
              }
              disabled={rollbackMutation.isPending || !rollbackReason.trim()}
              className="border-amber text-amber hover:bg-amber/10"
            >
              {rollbackMutation.isPending ? 'Rolling back...' : 'Confirm Rollback'}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Modal: Shadow Mode Configuration */}
      <Modal
        isOpen={isShadowModalOpen}
        onClose={() => setIsShadowModalOpen(false)}
        title={`Configure Shadow Evaluation: '${activeKey}'`}
        description="Shadow evaluation runs candidate rules asynchronously in the background during live traffic without affecting responses."
      >
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-titanium mb-1">
              Candidate Version for Shadow Evaluation
            </label>
            <select
              value={shadowTargetVersion}
              onChange={(e) => setShadowTargetVersion(e.target.value)}
              className="w-full px-3 py-2 text-xs bg-surface-elevated border border-moss/60 rounded-lg text-silver-frost focus:outline-none focus:border-emerald"
            >
              <option value="">Disable shadow evaluation</option>
              {versions?.map((v) => (
                <option key={v.id} value={v.versionNumber}>
                  v{v.versionNumber} ({v.status} - {v.rulesCount} rules)
                </option>
              ))}
            </select>
          </div>

          <div className="p-3 bg-surface-elevated border border-moss/30 rounded-lg text-xs text-titanium">
            Shadow decisions are logged to PostgreSQL for evaluation comparisons and never alter client HTTP responses.
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-moss/30">
            <Button variant="outline" onClick={() => setIsShadowModalOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={() =>
                shadowMutation.mutate({
                  key: activeKey,
                  shadowVer: shadowTargetVersion ? Number(shadowTargetVersion) : null,
                })
              }
              disabled={shadowMutation.isPending}
            >
              Save Configuration
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
export default PolicyStudio
