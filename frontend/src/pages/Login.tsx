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
    <div className="min-h-screen w-screen flex items-center justify-center bg-slate-950 p-4 relative overflow-hidden">
      {/* Background visual accents */}
      <div className="absolute top-1/4 left-1/3 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/3 w-96 h-96 bg-primary-700/10 rounded-full blur-3xl pointer-events-none" />

      <div className="w-full max-w-md relative z-10 space-y-6">
        {/* Brand identity header */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-gradient-to-tr from-indigo-600 to-indigo-400 text-white shadow-xl shadow-indigo-500/25 mb-2">
            <ShieldCheck className="w-8 h-8" />
          </div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Gatekeeper Admin</h1>
          <p className="text-xs text-slate-400">High-Performance API Gateway & Quota Management</p>
        </div>

        {/* Login form card */}
        <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 sm:p-8 shadow-2xl backdrop-blur-md space-y-5">
          <div className="space-y-1">
            <h2 className="text-base font-semibold text-white">Operator Sign In</h2>
            <p className="text-xs text-slate-400">Enter your administrative credentials to continue</p>
          </div>

          {errorMessage && (
            <div
              className={`p-3.5 rounded-lg border text-xs flex items-start gap-2.5 ${
                isLockedOut
                  ? 'bg-rose-950/40 border-rose-800/80 text-rose-300'
                  : 'bg-amber-950/40 border-amber-800/80 text-amber-300'
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
                  className="bg-slate-950 border-slate-700 text-white placeholder:text-slate-500"
                />
                <User className="w-4 h-4 text-slate-500 absolute right-3 top-8 pointer-events-none" />
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
                  className="bg-slate-950 border-slate-700 text-white placeholder:text-slate-500"
                />
                <Lock className="w-4 h-4 text-slate-500 absolute right-3 top-8 pointer-events-none" />
              </div>
            </div>

            <Button
              type="submit"
              variant="primary"
              size="lg"
              className="w-full mt-2 font-semibold shadow-md bg-indigo-600 hover:bg-indigo-500"
              isLoading={isSubmitting}
            >
              Sign In to Gateway
            </Button>
          </form>

          {/* Security & In-Memory Session Notice */}
          <div className="pt-4 border-t border-slate-800 text-[11px] text-slate-400 flex items-start gap-2 leading-relaxed">
            <Info className="w-4 h-4 text-indigo-400 shrink-0 mt-0.5" />
            <span>
              <strong>Security Policy:</strong> Gatekeeper strictly maintains admin JWTs in volatile memory. Tokens are never persisted to localStorage or sessionStorage. Page reload terminates the active session.
            </span>
          </div>
        </div>

        <div className="text-center text-xs text-slate-400">
          Gatekeeper Gateway Engine • Phase 4 Production Frontend
        </div>
      </div>
    </div>
  )
}
