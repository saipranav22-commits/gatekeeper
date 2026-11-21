import {
  CreatePolicyDefinitionRequest,
  CreatePolicyVersionRequest,
  PolicyActivationAuditDto,
  PolicyDefinitionDto,
  PolicyReplayRequest,
  PolicyReplayResponse,
  PolicySimulationRequest,
  PolicySimulationResponse,
  PolicyVersionDto,
} from '@/types/policy'
import {
  DEMO_POLICY_AUDITS,
  DEMO_POLICY_DEFINITIONS,
  DEMO_POLICY_VERSIONS,
  replayDemoPolicy,
  simulateDemoPolicy,
} from '../data/demoPolicyData'

let sessionDefinitions = [...DEMO_POLICY_DEFINITIONS]
const sessionVersions = { ...DEMO_POLICY_VERSIONS }

export const demoPolicyService = {
  async listDefinitions(): Promise<PolicyDefinitionDto[]> {
    return sessionDefinitions
  },

  async getDefinition(policyKey: string): Promise<PolicyDefinitionDto> {
    const def = sessionDefinitions.find((d) => d.policyKey === policyKey)
    if (!def) throw new Error(`Policy definition ${policyKey} not found`)
    return def
  },

  async createDefinition(request: CreatePolicyDefinitionRequest): Promise<PolicyDefinitionDto> {
    const newDef: PolicyDefinitionDto = {
      id: `pol-def-${Date.now().toString(36)}`,
      policyKey: request.policyKey,
      name: request.name,
      description: request.description || '',
      activeVersionNumber: null,
      shadowVersionNumber: null,
      versionLock: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    sessionDefinitions = [newDef, ...sessionDefinitions]
    sessionVersions[request.policyKey] = []
    return newDef
  },

  async listVersions(policyKey: string): Promise<PolicyVersionDto[]> {
    return sessionVersions[policyKey] || []
  },

  async getVersion(policyKey: string, versionNumber: number): Promise<PolicyVersionDto> {
    const versions = sessionVersions[policyKey] || []
    const ver = versions.find((v) => v.versionNumber === versionNumber)
    if (!ver) throw new Error(`Version ${versionNumber} not found for policy ${policyKey}`)
    return ver
  },

  async createVersion(policyKey: string, request: CreatePolicyVersionRequest): Promise<PolicyVersionDto> {
    const versions = sessionVersions[policyKey] || []
    const nextVerNum = versions.length > 0 ? Math.max(...versions.map((v) => v.versionNumber)) + 1 : 1
    const newVer: PolicyVersionDto = {
      id: `v-${Date.now().toString(36)}`,
      policyKey,
      versionNumber: nextVerNum,
      status: 'DRAFT',
      description: request.description || '',
      rules: request.rules,
      rulesCount: request.rules.length,
      createdAt: new Date().toISOString(),
      createdBy: 'demo-recruiter',
    }
    sessionVersions[policyKey] = [...versions, newVer]
    return newVer
  },

  async activateVersion(
    policyKey: string,
    versionNumber: number,
    _reason?: string
  ): Promise<PolicyDefinitionDto> {
    const def = await this.getDefinition(policyKey)
    def.activeVersionNumber = versionNumber
    def.versionLock += 1
    def.updatedAt = new Date().toISOString()
    return { ...def }
  },

  async rollback(
    policyKey: string,
    targetVersion?: number,
    _reason?: string
  ): Promise<PolicyDefinitionDto> {
    const def = await this.getDefinition(policyKey)
    if (targetVersion != null) {
      def.activeVersionNumber = targetVersion
    }
    def.versionLock += 1
    def.updatedAt = new Date().toISOString()
    return { ...def }
  },

  async configureShadow(
    policyKey: string,
    shadowVersionNumber: number | null
  ): Promise<PolicyDefinitionDto> {
    const def = await this.getDefinition(policyKey)
    def.shadowVersionNumber = shadowVersionNumber
    def.versionLock += 1
    def.updatedAt = new Date().toISOString()
    return { ...def }
  },

  async simulate(
    policyKey: string,
    request: PolicySimulationRequest
  ): Promise<PolicySimulationResponse> {
    return simulateDemoPolicy(policyKey, request)
  },

  async replay(
    policyKey: string,
    request: PolicyReplayRequest
  ): Promise<PolicyReplayResponse> {
    return replayDemoPolicy(policyKey, request)
  },

  async getAuditHistory(policyKey: string): Promise<PolicyActivationAuditDto[]> {
    return DEMO_POLICY_AUDITS.filter((a) => a.policyKey === policyKey)
  },
}
