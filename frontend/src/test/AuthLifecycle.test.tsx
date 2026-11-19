import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  apiClient,
  setAuthToken,
  getAuthToken,
  registerUnauthorizedHandler,
} from '@/lib/apiClient'

describe('Authentication Lifecycle & Token Isolation', () => {
  beforeEach(() => {
    setAuthToken(null)
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('strictly maintains access tokens in-memory and never persists to localStorage or sessionStorage', () => {
    const sensitiveJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy.signature'

    setAuthToken(sensitiveJwt)

    expect(getAuthToken()).toBe(sensitiveJwt)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('jwt')).toBeNull()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('clears in-memory token immediately when logout is called', () => {
    setAuthToken('token-to-be-destroyed')
    expect(getAuthToken()).toBe('token-to-be-destroyed')

    setAuthToken(null)
    expect(getAuthToken()).toBeNull()
  })

  it('invokes unauthorized handler callback on HTTP 401 responses', async () => {
    const unauthorizedSpy = vi.fn()
    registerUnauthorizedHandler(unauthorizedSpy)

    // Trigger error interceptor with a mock 401 rejection
    const mockAxiosError = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { message: 'JWT expired' },
      },
    }

    // Inspect the response interceptor reject handler safely
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const responseInterceptor = (apiClient.interceptors.response as any).handlers[0]
    
    try {
      await responseInterceptor?.rejected?.(mockAxiosError)
    } catch {
      // expected rethrow of normalized error
    }

    expect(unauthorizedSpy).toHaveBeenCalledTimes(1)
  })

  it('attaches Bearer token in Authorization header when token is set', async () => {
    setAuthToken('bearer-sample-jwt')

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const requestInterceptor = (apiClient.interceptors.request as any).handlers[0]
    const dummyConfig = { headers: {} as Record<string, string> }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const modifiedConfig = await requestInterceptor?.fulfilled?.(dummyConfig as any)

    expect(modifiedConfig?.headers?.['Authorization']).toBe('Bearer bearer-sample-jwt')
  })
})
