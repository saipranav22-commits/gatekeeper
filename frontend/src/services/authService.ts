import { apiClient, setAuthToken } from '@/lib/apiClient'
import { ChangePasswordRequest, LoginRequest, LoginResponse } from '@/types/auth'

export const authService = {
  async login(credentials: LoginRequest): Promise<LoginResponse> {
    const response = await apiClient.post<LoginResponse>('/api/v1/auth/login', credentials)
    if (response.data?.accessToken) {
      setAuthToken(response.data.accessToken)
    }
    return response.data
  },

  async changePassword(request: ChangePasswordRequest): Promise<LoginResponse> {
    const response = await apiClient.post<LoginResponse>('/api/v1/auth/change-password', request)
    if (response.data?.accessToken) {
      setAuthToken(response.data.accessToken)
    }
    return response.data
  },

  logout(): void {
    setAuthToken(null)
  },
}
