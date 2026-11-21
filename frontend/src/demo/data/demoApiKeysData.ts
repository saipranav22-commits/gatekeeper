import { ApiKeyCreationRequest, ApiKeyCreationResult, ApiKeyMetadataDto } from '@/types/apiKey'

const INITIAL_DEMO_KEYS: ApiKeyMetadataDto[] = [
  {
    id: 'key-acme-prod-01',
    consumerId: 'c-acme-fintech',
    name: 'Acme Payments Production Primary',
    keyPrefix: 'gk_demo_acme_7f9a',
    status: 'ACTIVE',
    scopes: ['gateway:invoke', 'read:orders', 'write:orders', 'analytics:read'],
    expiresAt: '2027-02-01T00:00:00Z',
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: '2026-11-21T18:42:15Z',
    version: 1,
    isDeleted: false,
    createdAt: '2026-02-01T10:05:00Z',
    updatedAt: '2026-11-21T18:42:15Z',
  },
  {
    id: 'key-acme-staging-02',
    consumerId: 'c-acme-fintech',
    name: 'Acme Sandbox Integration Key',
    keyPrefix: 'gk_demo_acme_14c3',
    status: 'ACTIVE',
    scopes: ['gateway:invoke', 'read:orders'],
    expiresAt: '2026-12-31T23:59:59Z',
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: '2026-11-20T12:11:00Z',
    version: 1,
    isDeleted: false,
    createdAt: '2026-02-15T11:00:00Z',
    updatedAt: '2026-11-20T12:11:00Z',
  },
  {
    id: 'key-global-orders-01',
    consumerId: 'c-global-logistics',
    name: 'Global Dispatch Gateway Token',
    keyPrefix: 'gk_demo_glo_98be',
    status: 'ACTIVE',
    scopes: ['gateway:invoke', 'write:orders'],
    expiresAt: '2027-03-10T00:00:00Z',
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: '2026-11-21T17:15:30Z',
    version: 1,
    isDeleted: false,
    createdAt: '2026-03-10T08:20:00Z',
    updatedAt: '2026-11-21T17:15:30Z',
  },
  {
    id: 'key-nova-telemetry-01',
    consumerId: 'c-nova-health',
    name: 'Nova Sensor Telemetry Ingest',
    keyPrefix: 'gk_demo_nov_54d1',
    status: 'ACTIVE',
    scopes: ['gateway:invoke', 'analytics:read'],
    expiresAt: '2027-04-05T00:00:00Z',
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: '2026-11-21T18:55:00Z',
    version: 1,
    isDeleted: false,
    createdAt: '2026-04-05T12:10:00Z',
    updatedAt: '2026-11-21T18:55:00Z',
  },
  {
    id: 'key-retail-pos-revoked',
    consumerId: 'c-retail-pulse',
    name: 'Legacy POS Terminal Key',
    keyPrefix: 'gk_demo_ret_00a2',
    status: 'REVOKED',
    scopes: ['gateway:invoke'],
    expiresAt: '2026-11-18T00:00:00Z',
    revokedAt: '2026-11-18T18:00:00Z',
    revocationReason: 'Rotated during security audit',
    lastUsedAt: '2026-11-18T17:58:12Z',
    version: 2,
    isDeleted: false,
    createdAt: '2026-05-18T16:25:00Z',
    updatedAt: '2026-11-18T18:00:00Z',
  },
]

let sessionKeys: ApiKeyMetadataDto[] = [...INITIAL_DEMO_KEYS]

export const getDemoKeysForConsumer = (consumerId: string): ApiKeyMetadataDto[] => {
  return sessionKeys.filter((k) => k.consumerId === consumerId && !k.isDeleted)
}

export const getDemoKeyById = (consumerId: string, keyId: string): ApiKeyMetadataDto | undefined => {
  return sessionKeys.find((k) => k.consumerId === consumerId && k.id === keyId && !k.isDeleted)
}

export const createDemoKey = (consumerId: string, request: ApiKeyCreationRequest): ApiKeyCreationResult => {
  const randomSuffix = Math.random().toString(36).substring(2, 6)
  const simulatedPrefix = `gk_demo_${randomSuffix}`
  const simulatedPlaintext = `gk_demo_simulated_${randomSuffix}_not_usable_in_production`

  const newKey: ApiKeyMetadataDto = {
    id: `key-demo-${Date.now().toString(36)}`,
    consumerId,
    name: request.name,
    keyPrefix: simulatedPrefix,
    status: 'ACTIVE',
    scopes: request.scopes && request.scopes.length > 0 ? request.scopes : ['gateway:invoke'],
    expiresAt: request.expiresAt || null,
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: null,
    version: 1,
    isDeleted: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }

  sessionKeys = [newKey, ...sessionKeys]

  return {
    metadata: newKey,
    plaintextKey: simulatedPlaintext,
  }
}

export const revokeDemoKey = (consumerId: string, keyId: string, reason?: string): ApiKeyMetadataDto => {
  const target = getDemoKeyById(consumerId, keyId)
  if (!target) throw new Error(`API Key ${keyId} not found`)
  target.status = 'REVOKED'
  target.revokedAt = new Date().toISOString()
  target.revocationReason = reason || 'Revoked via demo console'
  target.updatedAt = new Date().toISOString()
  return { ...target }
}

export const resetDemoKeys = (): void => {
  sessionKeys = [...INITIAL_DEMO_KEYS]
}
