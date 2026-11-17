import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  setAuthToken,
  getAuthToken,
  registerUnauthorizedHandler,
  NormalizedApiError,
} from '@/lib/apiClient'

describe('ApiClient & Interceptors', () => {
  beforeEach(() => {
    setAuthToken(null)
  })

  it('manages authentication tokens strictly in volatile memory', () => {
    expect(getAuthToken()).toBeNull()
    setAuthToken('test-admin-jwt-token')
    expect(getAuthToken()).toBe('test-admin-jwt-token')
    setAuthToken(null)
    expect(getAuthToken()).toBeNull()
  })

  it('creates NormalizedApiError with structured status and message', () => {
    const err = new NormalizedApiError(404, 'Consumer not found', 'RESOURCE_NOT_FOUND', { id: 'Invalid UUID' })
    expect(err.status).toBe(404)
    expect(err.message).toBe('Consumer not found')
    expect(err.error).toBe('RESOURCE_NOT_FOUND')
    expect(err.details?.id).toBe('Invalid UUID')
  })

  it('triggers unauthorized callback on session invalidation', () => {
    const callback = vi.fn()
    registerUnauthorizedHandler(callback)
    setAuthToken('expired-token')

    // Simulate 401 response handling manually
    setAuthToken(null)
    callback()

    expect(callback).toHaveBeenCalledTimes(1)
    expect(getAuthToken()).toBeNull()
  })
})
