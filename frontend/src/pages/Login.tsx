import React, { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { useAuth } from '@/context/AuthContext'
import { Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import { ShieldCheck, Lock, User, AlertCircle, Info } from 'lucide-react'
import { NormalizedApiError } from '@/lib/apiClient'
import { isDemoMode } from '@/config/appMode'

const loginSchema = z.object({
  username: z
    .string()
    .trim()
    .min(3, 'Username or email must be at least 3 characters')
    .max(100, 'Username or email cannot exceed 100 characters'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .max(100, 'Password cannot exceed 100 characters'),
})

type LoginFormValues = z.infer<typeof loginSchema>

export const Login: React.FC = () => {
  const { login, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [isLockedOut, setIsLockedOut] = useState<boolean>(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: '',
      password: '',
    },
  })

  // Redirect if already authenticated
  React.useEffect(() => {
    if (isAuthenticated) {
      const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/'
      navigate(from, { replace: true })
    }
  }, [isAuthenticated, navigate, location])

  const onSubmit = async (values: LoginFormValues) => {
    setErrorMessage(null)
    setIsLockedOut(false)

    try {
      await login(values)
      const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/'
      navigate(from, { replace: true })
    } catch (err) {
      const apiErr = err as NormalizedApiError
      const status = apiErr?.status
      if (status === 429) {
        setIsLockedOut(true)
        setErrorMessage(
          apiErr.message ||
            'Account or IP locked due to excessive failed attempts. Please try again later.'
        )
      } else if (status === 401) {
        setErrorMessage('Invalid username or password. Please verify credentials.')
      } else if (apiErr?.message) {
        setErrorMessage(apiErr.message)
      } else {
        setErrorMessage('Unable to reach Gatekeeper authentication service.')
      }
    }
  }

  return (
    <div className="min-h-screen w-screen flex items-center justify-center bg-canvas p-4 relative overflow-hidden">
      {/* Obsidian Titanium atmospheric radial accents */}
      <div className="absolute top-1/4 left-1/3 w-96 h-96 bg-emerald/5 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/3 w-96 h-96 bg-moss/10 rounded-full blur-3xl pointer-events-none" />

      <div className="w-full max-w-md relative z-10 space-y-6">
        {/* Brand identity header */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-surface border border-emerald/30 text-emerald shadow-emerald-sm mb-2">
            <ShieldCheck className="w-8 h-8" />
          </div>
          <h1 className="text-2xl font-bold text-silver-frost tracking-tight">Gatekeeper Admin</h1>
          <p className="text-xs text-titanium font-mono">High-Performance API Gateway & Quota Management</p>
        </div>

        {/* Portfolio Recruiter Direct Access in Demo Mode */}
        {isDemoMode() && (
          <div className="bg-surface border border-emerald/50 rounded-2xl p-6 shadow-emerald-sm backdrop-blur-md space-y-4">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 rounded bg-emerald/20 text-emerald text-xs font-mono font-bold border border-emerald/30">
                INTERACTIVE DEMO MODE
              </span>
            </div>
            <div className="space-y-1">
              <h2 className="text-base font-semibold text-silver-frost">Portfolio Recruiter Access</h2>
              <p className="text-xs text-titanium font-mono">
                No credentials required. Enter the Gatekeeper Control Plane directly to explore live-fidelity architecture, metrics, and policy simulation.
              </p>
            </div>
            <Button
              type="button"
              className="w-full bg-emerald hover:bg-emerald/90 text-canvas font-mono font-bold"
              onClick={async () => {
                await login({ username: 'demo-recruiter', password: 'demo-password' })
                navigate('/', { replace: true })
              }}
            >
              Enter Demo Console →
            </Button>
          </div>
        )}

        {/* Login form card */}
        <div className="bg-surface border border-moss/60 rounded-2xl p-6 sm:p-8 shadow-modal-dark backdrop-blur-md space-y-5">
          <div className="space-y-1">
            <h2 className="text-base font-semibold text-silver-frost">Operator Sign In</h2>
            <p className="text-xs text-titanium">Enter your administrative credentials to continue</p>
          </div>

          {errorMessage && (
            <div
              className={`p-3.5 rounded-lg border text-xs flex items-start gap-2.5 ${
                isLockedOut
                  ? 'bg-signal-red/10 border-signal-red/30 text-signal-red'
                  : 'bg-warning-amber/10 border-warning-amber/30 text-warning-amber'
              }`}
              role="alert"
            >
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <div className="leading-relaxed">{errorMessage}</div>
            </div>
          )}

          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div>
              <div className="relative">
                <Input
                  label="Username or Email"
                  placeholder="admin or admin@gatekeeper.local"
                  autoComplete="username"
                  {...register('username')}
                  error={errors.username?.message}
                  className="bg-input border-moss/60 text-silver-frost placeholder:text-titanium-muted"
                />
                <User className="w-4 h-4 text-titanium absolute right-3 top-8 pointer-events-none" />
              </div>
            </div>

            <div>
              <div className="relative">
                <Input
                  label="Password"
                  type="password"
                  placeholder="••••••••••••"
                  autoComplete="current-password"
                  {...register('password')}
                  error={errors.password?.message}
                  className="bg-input border-moss/60 text-silver-frost placeholder:text-titanium-muted"
                />
                <Lock className="w-4 h-4 text-titanium absolute right-3 top-8 pointer-events-none" />
              </div>
            </div>

            <Button
              type="submit"
              variant="primary"
              size="lg"
              className="w-full mt-2"
              isLoading={isSubmitting}
            >
              Sign In to Gateway
            </Button>
          </form>

          {/* Security & In-Memory Session Notice */}
          <div className="pt-4 border-t border-moss-subtle text-[11px] text-titanium flex items-start gap-2 leading-relaxed bg-surface-elevated/40 p-3 rounded-lg border border-moss-subtle/50">
            <Info className="w-4 h-4 text-emerald shrink-0 mt-0.5" />
            <span>
              <strong>Security Policy:</strong> Gatekeeper strictly maintains admin JWTs in volatile memory. Tokens are never persisted to localStorage or sessionStorage. Page reload terminates the active session.
            </span>
          </div>
        </div>

        <div className="text-center text-xs text-titanium font-mono">
          Gatekeeper Gateway Engine • Phase 7 Production Control Plane
        </div>
      </div>
    </div>
  )
}
