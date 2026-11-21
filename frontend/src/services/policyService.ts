import { apiClient } from '@/lib/apiClient'
import {
  ConfigureShadowPolicyRequest,
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
import { isDemoMode } from '@/config/appMode'
import { demoPolicyService } from '@/demo/services/demoPolicyService'

export const policyService = {
  /**
   * Retrieves all policy definitions registered in the gateway.
   */
  async listDefinitions(): Promise<PolicyDefinitionDto[]> {
    if (isDemoMode()) {
      return demoPolicyService.listDefinitions()
    }
    const response = await apiClient.get<PolicyDefinitionDto[]>('/api/v1/policies')
    return response.data
  },

  /**
   * Retrieves a single policy definition by unique policyKey.
   */
  async getDefinition(policyKey: string): Promise<PolicyDefinitionDto> {
    if (isDemoMode()) {
      return demoPolicyService.getDefinition(policyKey)
    }
    const response = await apiClient.get<PolicyDefinitionDto>(`/api/v1/policies/${encodeURIComponent(policyKey)}`)
    return response.data
  },

  /**
   * Registers a new policy definition container with a unique policyKey.
   */
  async createDefinition(request: CreatePolicyDefinitionRequest): Promise<PolicyDefinitionDto> {
    if (isDemoMode()) {
      return demoPolicyService.createDefinition(request)
    }
    const response = await apiClient.post<PolicyDefinitionDto>('/api/v1/policies', request)
    return response.data
  },

  /**
   * Lists all immutable versions created under the specified policy definition.
   */
  async listVersions(policyKey: string): Promise<PolicyVersionDto[]> {
    if (isDemoMode()) {
      return demoPolicyService.listVersions(policyKey)
    }
    const response = await apiClient.get<PolicyVersionDto[]>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/versions`
    )
    return response.data
  },

  /**
   * Retrieves a specific immutable policy version by version number.
   */
  async getVersion(policyKey: string, versionNumber: number): Promise<PolicyVersionDto> {
    if (isDemoMode()) {
      return demoPolicyService.getVersion(policyKey, versionNumber)
    }
    const response = await apiClient.get<PolicyVersionDto>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/versions/${versionNumber}`
    )
    return response.data
  },

  /**
   * Creates a new immutable candidate version with strictly validated rules.
   */
  async createVersion(
    policyKey: string,
    request: CreatePolicyVersionRequest
  ): Promise<PolicyVersionDto> {
    if (isDemoMode()) {
      return demoPolicyService.createVersion(policyKey, request)
    }
    const response = await apiClient.post<PolicyVersionDto>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/versions`,
      request
    )
    return response.data
  },

  /**
   * Atomically activates a candidate version, retiring the previous active version.
   */
  async activateVersion(
    policyKey: string,
    versionNumber: number,
    reason?: string
  ): Promise<PolicyDefinitionDto> {
    if (isDemoMode()) {
      return demoPolicyService.activateVersion(policyKey, versionNumber, reason)
    }
    const response = await apiClient.post<PolicyDefinitionDto>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/versions/${versionNumber}/activate`,
      null,
      { params: reason ? { reason } : undefined }
    )
    return response.data
  },

  /**
   * Rolls back active policy to a previous validated immutable version.
   */
  async rollback(
    policyKey: string,
    targetVersion?: number,
    reason?: string
  ): Promise<PolicyDefinitionDto> {
    if (isDemoMode()) {
      return demoPolicyService.rollback(policyKey, targetVersion, reason)
    }
    const response = await apiClient.post<PolicyDefinitionDto>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/rollback`,
      null,
      {
        params: {
          ...(targetVersion != null ? { targetVersion } : {}),
          ...(reason ? { reason } : {}),
        },
      }
    )
    return response.data
  },

  /**
   * Sets or clears the shadow candidate version evaluated asynchronously during live traffic.
   */
  async configureShadow(
    policyKey: string,
    shadowVersionNumber: number | null
  ): Promise<PolicyDefinitionDto> {
    if (isDemoMode()) {
      return demoPolicyService.configureShadow(policyKey, shadowVersionNumber)
    }
    const payload: ConfigureShadowPolicyRequest = { shadowVersionNumber }
    const response = await apiClient.post<PolicyDefinitionDto>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/shadow`,
      payload
    )
    return response.data
  },

  /**
   * Executes a zero-side-effect simulation against candidate rules or a candidate version.
   * Never mutates Redis quotas or contacts downstream services.
   */
  async simulate(
    policyKey: string,
    request: PolicySimulationRequest
  ): Promise<PolicySimulationResponse> {
    if (isDemoMode()) {
      return demoPolicyService.simulate(policyKey, request)
    }
    const response = await apiClient.post<PolicySimulationResponse>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/simulate`,
      request
    )
    return response.data
  },

  /**
   * Executes historical replay impact analysis against actual logged telemetry events.
   */
  async replay(
    policyKey: string,
    request: PolicyReplayRequest
  ): Promise<PolicyReplayResponse> {
    if (isDemoMode()) {
      return demoPolicyService.replay(policyKey, request)
    }
    const response = await apiClient.post<PolicyReplayResponse>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/replay`,
      request
    )
    return response.data
  },

  /**
   * Retrieves the immutable chronological activation, rollback, and shadow audit log.
   */
  async getAuditHistory(policyKey: string): Promise<PolicyActivationAuditDto[]> {
    if (isDemoMode()) {
      return demoPolicyService.getAuditHistory(policyKey)
    }
    const response = await apiClient.get<PolicyActivationAuditDto[]>(
      `/api/v1/policies/${encodeURIComponent(policyKey)}/audit`
    )
    return response.data
  },
}
