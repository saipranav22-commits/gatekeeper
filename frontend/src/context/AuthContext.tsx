import React, { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { AdminProfileDto, AdminRole, ChangePasswordRequest, LoginRequest } from '@/types/auth'
import { authService } from '@/services/authService'
import { registerUnauthorizedHandler, setAuthToken } from '@/lib/apiClient'

interface AuthContextType {
  admin: AdminProfileDto | null
  token: string | null
  isAuthenticated: boolean
  isLoading: boolean
  login: (credentials: LoginRequest) => Promise<void>
  logout: () => void
  changePassword: (request: ChangePasswordRequest) => Promise<void>
  hasRole: (roles: AdminRole | AdminRole[]) => boolean
  canMutate: () => boolean
  isSuperAdmin: () => boolean
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [admin, setAdmin] = useState<AdminProfileDto | null>(null)
  const [token, setToken] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState<boolean>(false)

  const logout = useCallback(() => {
    authService.logout()
    setAuthToken(null)
    setToken(null)
    setAdmin(null)
  }, [])

  useEffect(() => {
    // Register 401 callback to immediately clear in-memory authentication
    registerUnauthorizedHandler(() => {
      logout()
    })
  }, [logout])

  const login = async (credentials: LoginRequest): Promise<void> => {
    setIsLoading(true)
    try {
      const response = await authService.login(credentials)
      setToken(response.accessToken)
      setAdmin(response.admin)
    } finally {
      setIsLoading(false)
    }
  }

  const changePassword = async (request: ChangePasswordRequest): Promise<void> => {
    setIsLoading(true)
    try {
      const response = await authService.changePassword(request)
      setToken(response.accessToken)
      setAdmin(response.admin)
    } finally {
      setIsLoading(false)
    }
  }

  const hasRole = useCallback(
    (roles: AdminRole | AdminRole[]): boolean => {
      if (!admin) return false
      const requiredRoles = Array.isArray(roles) ? roles : [roles]
      return requiredRoles.includes(admin.role)
    },
    [admin]
  )

  const canMutate = useCallback((): boolean => {
    if (!admin) return false
    // ROLE_ANALYST is read-only for management entities and keys
    return admin.role === 'ROLE_SUPER_ADMIN' || admin.role === 'ROLE_ADMIN'
  }, [admin])

  const isSuperAdmin = useCallback((): boolean => {
    return admin?.role === 'ROLE_SUPER_ADMIN'
  }, [admin])

  return (
    <AuthContext.Provider
      value={{
        admin,
        token,
        isAuthenticated: !!token && !!admin,
        isLoading,
        login,
        logout,
        changePassword,
        hasRole,
        canMutate,
        isSuperAdmin,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
