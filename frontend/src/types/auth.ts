export type AdminRole = 'ROLE_SUPER_ADMIN' | 'ROLE_ADMIN' | 'ROLE_ANALYST'

export interface AdminProfileDto {
  id: string
  username: string
  email: string
  role: AdminRole
  mustChangePassword: boolean
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  expiresAt: string
  admin: AdminProfileDto
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}
