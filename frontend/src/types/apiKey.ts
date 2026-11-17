export type ApiKeyStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'DEACTIVATED'

export interface ApiKeyMetadataDto {
  id: string
  consumerId: string
  name: string
  keyPrefix: string
  status: ApiKeyStatus
  scopes: string[]
  expiresAt: string | null
  revokedAt: string | null
  revocationReason: string | null
  lastUsedAt: string | null
  version: number
  isDeleted: boolean
  createdAt: string
  updatedAt: string
}

export interface ApiKeyCreationRequest {
  name: string
  scopes: string[]
  expiresAt?: string | null
}

export interface ApiKeyCreationResult {
  metadata: ApiKeyMetadataDto
  plaintextKey: string
}

export interface RevokeApiKeyRequest {
  reason?: string
}
