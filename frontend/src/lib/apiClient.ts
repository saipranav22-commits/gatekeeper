import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { ApiErrorResponse } from '@/types/api'

// Base URL: Uses VITE_API_BASE_URL if configured, otherwise falls back to empty string (proxied by Vite)
const baseURL = import.meta.env.VITE_API_BASE_URL || ''

export const apiClient: AxiosInstance = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
  timeout: 15000,
})

// Strictly in-memory token store — NEVER written to localStorage or sessionStorage
let inMemoryToken: string | null = null
let onUnauthorizedCallback: (() => void) | null = null

export function setAuthToken(token: string | null): void {
  inMemoryToken = token
}

export function getAuthToken(): string | null {
  return inMemoryToken
}

export function registerUnauthorizedHandler(callback: () => void): void {
  onUnauthorizedCallback = callback
}

// Request Interceptor: Injects Authorization Bearer header from memory
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    if (inMemoryToken) {
      config.headers.Authorization = `Bearer ${inMemoryToken}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

export class NormalizedApiError extends Error {
  status: number
  error: string
  details?: Record<string, string>
  timestamp?: string
  path?: string

  constructor(status: number, message: string, error: string = 'ApiError', details?: Record<string, string>, path?: string) {
    super(message)
    this.name = 'NormalizedApiError'
    this.status = status
    this.error = error
    this.details = details
    this.path = path
  }
}

// Response Interceptor: Normalizes errors and detects 401 session invalidation
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorResponse>) => {
    if (!error.response) {
      // Network failure, CORS drop, or offline
      return Promise.reject(
        new NormalizedApiError(
          0,
          error.message || 'Unable to connect to the Gatekeeper backend. Please verify the server is running.',
          'NETWORK_ERROR'
        )
      )
    }

    const { status, data } = error.response
    let message = 'An unexpected error occurred'
    let errorType = 'UNKNOWN_ERROR'
    let details: Record<string, string> | undefined

    if (data && typeof data === 'object') {
      message = data.message || data.error || message
      errorType = data.error || `HTTP_${status}`
      details = data.validationErrors
    } else if (typeof data === 'string' && data) {
      message = data
    }

    if (status === 401) {
      // Invalidate in-memory token and invoke session expiry callback
      inMemoryToken = null
      if (onUnauthorizedCallback) {
        onUnauthorizedCallback()
      }
      message = message || 'Session expired or invalid credentials. Please log in again.'
    } else if (status === 403) {
      message = message || 'Access denied. You do not have permission to perform this action.'
    } else if (status === 404) {
      message = message || 'The requested resource was not found.'
    } else if (status === 409) {
      message = message || 'Resource conflict. The record already exists or has an active conflict.'
    } else if (status === 429) {
      message = message || 'Rate limit exceeded. Too many requests have been submitted.'
    } else if (status >= 500) {
      message = message || 'Gatekeeper backend encountered an internal error.'
    }

    return Promise.reject(new NormalizedApiError(status, message, errorType, details, data?.path))
  }
)
