import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Login } from '@/pages/Login'
import { AuthProvider } from '@/context/AuthContext'
import { authService } from '@/services/authService'

vi.mock('@/services/authService', () => ({
  authService: {
    login: vi.fn(),
    logout: vi.fn(),
    changePassword: vi.fn(),
  },
}))

describe('Login Screen', () => {
  it('renders login form elements and policy notice', () => {
    render(
      <AuthProvider>
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      </AuthProvider>
    )

    expect(screen.getByRole('heading', { name: /Gatekeeper Admin/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Operator Sign In/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/Username/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Sign In to Gateway/i })).toBeInTheDocument()
    expect(screen.getByText(/Gatekeeper strictly maintains admin JWTs in volatile memory/i)).toBeInTheDocument()
  })

  it('validates required fields before submitting', async () => {
    render(
      <AuthProvider>
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      </AuthProvider>
    )

    const submitBtn = screen.getByRole('button', { name: /Sign In to Gateway/i })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByText(/Username must be at least 3 characters/i)).toBeInTheDocument()
      expect(screen.getByText(/Password must be at least 8 characters/i)).toBeInTheDocument()
    })
  })

  it('submits credentials on valid form completion', async () => {
    vi.mocked(authService.login).mockResolvedValueOnce({
      accessToken: 'valid-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      expiresAt: '2026-11-18T00:00:00Z',
      admin: {
        id: 'uuid-1',
        username: 'admin',
        email: 'admin@gatekeeper.local',
        role: 'ROLE_ADMIN',
        mustChangePassword: false,
      },
    })

    render(
      <AuthProvider>
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      </AuthProvider>
    )

    fireEvent.change(screen.getByLabelText(/Username/i), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText(/Password/i), { target: { value: 'password123' } })
    fireEvent.click(screen.getByRole('button', { name: /Sign In to Gateway/i }))

    await waitFor(() => {
      expect(authService.login).toHaveBeenCalledWith({
        username: 'admin',
        password: 'password123',
      })
    })
  })
})
