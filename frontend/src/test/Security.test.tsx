import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Security } from '@/pages/Security'
import * as AuthContextModule from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'
import { AdminProfileDto } from '@/types/auth'

const mockAdmin: AdminProfileDto = {
  id: 'adm-001',
  username: 'sre_admin',
  email: 'sre@gatekeeper.local',
  role: 'ROLE_SUPER_ADMIN',
  mustChangePassword: false,
}

const renderSecurity = (adminOverrides?: Partial<AdminProfileDto>, changePasswordMock = vi.fn()) => {
  const admin = { ...mockAdmin, ...adminOverrides }

  vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
    admin,
    token: 'mock-token',
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    changePassword: changePasswordMock,
    hasRole: vi.fn().mockReturnValue(true),
    canMutate: vi.fn().mockReturnValue(true),
    isSuperAdmin: vi.fn().mockReturnValue(true),
  })

  return {
    ...render(
      <ToastProvider>
        <Security />
      </ToastProvider>
    ),
    changePasswordMock,
  }
}

describe('Security Page Component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders administrator profile details and session architecture', () => {
    renderSecurity()

    expect(screen.getByText('Security & Account Settings')).toBeInTheDocument()
    expect(screen.getByText('sre_admin')).toBeInTheDocument()
    expect(screen.getByText('sre@gatekeeper.local')).toBeInTheDocument()
    expect(screen.getByText('SUPER ADMIN')).toBeInTheDocument()
    expect(screen.getByText(/Token Isolation:/i)).toBeInTheDocument()
    expect(screen.getByText(/held strictly in memory/i)).toBeInTheDocument()
  })

  it('renders mandatory password change alert when required', () => {
    renderSecurity({ mustChangePassword: true })

    expect(screen.getByText('Action Required: Change Initial Password')).toBeInTheDocument()
  })

  it('validates password length and matching confirmation', async () => {
    renderSecurity()

    const oldInput = screen.getByLabelText(/Current Password/i)
    const newInput = screen.getByLabelText(/^New Password/i)
    const confirmInput = screen.getByLabelText(/Confirm New Password/i)
    const submitBtn = screen.getByRole('button', { name: /Update Password/i })

    // 1. Empty fields
    fireEvent.click(submitBtn)
    await waitFor(() => {
      expect(screen.getByText('Current password is required')).toBeInTheDocument()
    })

    // 2. Short password (<12 chars)
    fireEvent.change(oldInput, { target: { value: 'OldPassword123!' } })
    fireEvent.change(newInput, { target: { value: 'short' } })
    fireEvent.change(confirmInput, { target: { value: 'short' } })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByText('New password must be at least 12 characters')).toBeInTheDocument()
    })

    // 3. Password mismatch
    fireEvent.change(newInput, { target: { value: 'ValidPassword123!' } })
    fireEvent.change(confirmInput, { target: { value: 'DifferentPassword123!' } })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByText('New passwords do not match')).toBeInTheDocument()
    })
  })

  it('calls changePassword and triggers success toast on valid submission', async () => {
    const mockChangePassword = vi.fn().mockResolvedValue({})
    renderSecurity(undefined, mockChangePassword)

    fireEvent.change(screen.getByLabelText(/Current Password/i), { target: { value: 'CurrentPassword123!' } })
    fireEvent.change(screen.getByLabelText(/^New Password/i), { target: { value: 'NewSecurePassword123!' } })
    fireEvent.change(screen.getByLabelText(/Confirm New Password/i), { target: { value: 'NewSecurePassword123!' } })

    fireEvent.click(screen.getByRole('button', { name: /Update Password/i }))

    await waitFor(() => {
      expect(mockChangePassword).toHaveBeenCalledWith({
        oldPassword: 'CurrentPassword123!',
        newPassword: 'NewSecurePassword123!',
      })
      expect(screen.getByText('Password Changed')).toBeInTheDocument()
    })
  })
})
