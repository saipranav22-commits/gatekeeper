import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { ProtectedRoute } from '@/components/auth/ProtectedRoute'
import * as AuthContextModule from '@/context/AuthContext'

describe('ProtectedRoute Security Guard', () => {
  it('redirects unauthenticated operators to /login', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      isAuthenticated: false,
      admin: null,
      token: null,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: vi.fn().mockReturnValue(false),
      canMutate: vi.fn().mockReturnValue(false),
      isSuperAdmin: vi.fn().mockReturnValue(false),
    })

    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/login" element={<div>Login Page Screen</div>} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>Secure Platform Dashboard</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    )

    expect(screen.getByText('Login Page Screen')).toBeInTheDocument()
    expect(screen.queryByText('Secure Platform Dashboard')).not.toBeInTheDocument()
  })

  it('renders protected content when operator is authenticated', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      isAuthenticated: true,
      admin: {
        id: 'admin-uuid',
        username: 'admin',
        email: 'admin@gatekeeper.local',
        role: 'ROLE_ADMIN',
        mustChangePassword: false,
      },
      token: 'valid-jwt',
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: vi.fn().mockReturnValue(true),
      canMutate: vi.fn().mockReturnValue(true),
      isSuperAdmin: vi.fn().mockReturnValue(false),
    })

    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>Secure Platform Dashboard</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    )

    expect(screen.getByText('Secure Platform Dashboard')).toBeInTheDocument()
  })

  it('blocks access and displays error if role does not match requiredRoles', () => {
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      isAuthenticated: true,
      admin: {
        id: 'analyst-uuid',
        username: 'analyst',
        email: 'analyst@gatekeeper.local',
        role: 'ROLE_ANALYST',
        mustChangePassword: false,
      },
      token: 'valid-jwt',
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: vi.fn().mockReturnValue(false),
      canMutate: vi.fn().mockReturnValue(false),
      isSuperAdmin: vi.fn().mockReturnValue(false),
    })

    render(
      <MemoryRouter initialEntries={['/superadmin-only']}>
        <Routes>
          <Route
            path="/superadmin-only"
            element={
              <ProtectedRoute requiredRoles="ROLE_SUPER_ADMIN">
                <div>Superadmin Secret Vault</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    )

    expect(screen.getByText('Access Restricted')).toBeInTheDocument()
    expect(screen.queryByText('Superadmin Secret Vault')).not.toBeInTheDocument()
  })
})
