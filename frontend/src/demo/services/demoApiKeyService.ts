import { ApiKeyCreationRequest, ApiKeyCreationResult, ApiKeyMetadataDto } from '@/types/apiKey'
import {
  createDemoKey,
  getDemoKeyById,
  getDemoKeysForConsumer,
  revokeDemoKey,
} from '../data/demoApiKeysData'

export const demoApiKeyService = {
  async listKeys(consumerId: string): Promise<ApiKeyMetadataDto[]> {
    return getDemoKeysForConsumer(consumerId)
  },

  async getKey(consumerId: string, keyId: string): Promise<ApiKeyMetadataDto> {
    const key = getDemoKeyById(consumerId, keyId)
    if (!key) {
      throw new Error(`API Key ${keyId} not found`)
    }
    return key
  },

  async createKey(consumerId: string, request: ApiKeyCreationRequest): Promise<ApiKeyCreationResult> {
    return createDemoKey(consumerId, request)
  },

  async revokeKey(consumerId: string, keyId: string, reason?: string): Promise<ApiKeyMetadataDto> {
    return revokeDemoKey(consumerId, keyId, reason)
  },
}
