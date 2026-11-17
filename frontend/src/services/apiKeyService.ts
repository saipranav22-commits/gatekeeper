import { apiClient } from '@/lib/apiClient'
import { ApiKeyCreationRequest, ApiKeyCreationResult, ApiKeyMetadataDto } from '@/types/apiKey'

export const apiKeyService = {
  async listKeys(consumerId: string): Promise<ApiKeyMetadataDto[]> {
    const response = await apiClient.get<ApiKeyMetadataDto[]>(`/api/v1/consumers/${consumerId}/keys`)
    return response.data
  },

  async getKey(consumerId: string, keyId: string): Promise<ApiKeyMetadataDto> {
    const response = await apiClient.get<ApiKeyMetadataDto>(`/api/v1/consumers/${consumerId}/keys/${keyId}`)
    return response.data
  },

  /**
   * Generates a new API key. The plaintextKey is returned ONLY once.
   * Callers must NOT cache or persist plaintextKey.
   */
  async createKey(consumerId: string, request: ApiKeyCreationRequest): Promise<ApiKeyCreationResult> {
    const response = await apiClient.post<ApiKeyCreationResult>(
      `/api/v1/consumers/${consumerId}/keys`,
      request
    )
    return response.data
  },

  async revokeKey(consumerId: string, keyId: string, reason?: string): Promise<ApiKeyMetadataDto> {
    const response = await apiClient.post<ApiKeyMetadataDto>(
      `/api/v1/consumers/${consumerId}/keys/${keyId}/revoke`,
      reason ? { reason } : {}
    )
    return response.data
  },
}
