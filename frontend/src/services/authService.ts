import { apiClient, setAuthToken } from '@/lib/apiClient'
import { ChangePasswordRequest, LoginRequest, LoginResponse } from '@/types/auth'

export const authService = {
  async login(credentials: LoginRequest): Promise<LoginResponse> {
    const response = await apiClient.post<LoginResponse>('/api/v1/auth/login', credentials)
    const token = response.data?.accessToken || (response.data as unknown as { token?: string })?.token
    if (token) {
      setAuthToken(token)
    }
    return response.data
  },

  async changePassword(request: ChangePasswordRequest): Promise<LoginResponse> {
    const response = await apiClient.post<LoginResponse>('/api/v1/auth/change-password', request)
    const token = response.data?.accessToken || (response.data as unknown as { token?: string })?.token
    if (token) {
      setAuthToken(token)
    }
    return response.data
  },

  logout(): void {
    setAuthToken(null)
  },
}
