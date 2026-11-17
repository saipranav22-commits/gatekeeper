import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { AuthProvider, useAuth } from '@/context/AuthContext'
import { authService } from '@/services/authService'

vi.mock('@/services/authService', () => ({
  authService: {
    login: vi.fn(),
    logout: vi.fn(),
    changePassword: vi.fn(),
  },
}))

describe('AuthContext & RBAC Rules', () => {
  const wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
    <AuthProvider>{children}</AuthProvider>
  )

  it('initializes in an unauthenticated state', () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.admin).toBeNull()
    expect(result.current.token).toBeNull()
  })

  it('updates in-memory state upon successful login', async () => {
    const mockResponse = {
      accessToken: 'jwt-xyz-123',
      tokenType: 'Bearer',
      expiresIn: 86400,
      expiresAt: '2026-11-18T12:00:00Z',
      admin: {
        id: 'admin-uuid-1',
        username: 'admin',
        email: 'admin@gatekeeper.local',
        role: 'ROLE_SUPER_ADMIN' as const,
        mustChangePassword: false,
      },
    }

    vi.mocked(authService.login).mockResolvedValueOnce(mockResponse)

    const { result } = renderHook(() => useAuth(), { wrapper })

    await act(async () => {
      await result.current.login({ username: 'admin', password: 'password123' })
    })

    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.admin?.username).toBe('admin')
    expect(result.current.hasRole('ROLE_SUPER_ADMIN')).toBe(true)
    expect(result.current.canMutate()).toBe(true)
    expect(result.current.isSuperAdmin()).toBe(true)
  })

  it('enforces RBAC mutation restriction for ROLE_ANALYST', async () => {
    const mockResponse = {
      accessToken: 'jwt-analyst-123',
      tokenType: 'Bearer',
      expiresIn: 86400,
      expiresAt: '2026-11-18T12:00:00Z',
      admin: {
        id: 'analyst-uuid-2',
        username: 'analyst',
        email: 'analyst@gatekeeper.local',
        role: 'ROLE_ANALYST' as const,
        mustChangePassword: false,
      },
    }

    vi.mocked(authService.login).mockResolvedValueOnce(mockResponse)

    const { result } = renderHook(() => useAuth(), { wrapper })

    await act(async () => {
      await result.current.login({ username: 'analyst', password: 'password123' })
    })

    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.hasRole('ROLE_ANALYST')).toBe(true)
    // ROLE_ANALYST must NOT be permitted to mutate consumers or API keys!
    expect(result.current.canMutate()).toBe(false)
    expect(result.current.isSuperAdmin()).toBe(false)
  })

  it('clears in-memory session on logout', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })

    act(() => {
      result.current.logout()
    })

    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.admin).toBeNull()
    expect(result.current.token).toBeNull()
  })
})
