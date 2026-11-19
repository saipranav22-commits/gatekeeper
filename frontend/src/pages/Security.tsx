import React, { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Badge } from '@/components/ui/Badge'
import { Shield, User, Lock, AlertTriangle, CheckCircle2, ShieldAlert } from 'lucide-react'
import { NormalizedApiError } from '@/lib/apiClient'

const changePasswordSchema = z
  .object({
    oldPassword: z.string().min(1, 'Current password is required'),
    newPassword: z
      .string()
      .min(12, 'New password must be at least 12 characters')
      .max(100, 'New password cannot exceed 100 characters'),
    confirmPassword: z.string().min(1, 'Please confirm your new password'),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'New passwords do not match',
    path: ['confirmPassword'],
  })

type ChangePasswordFormValues = z.infer<typeof changePasswordSchema>

export const Security: React.FC = () => {
  const { admin, changePassword } = useAuth()
  const { showToast } = useToast()
  const [serverError, setServerError] = useState<string | null>(null)
  const [successNotice, setSuccessNotice] = useState<boolean>(false)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: {
      oldPassword: '',
      newPassword: '',
      confirmPassword: '',
    },
  })

  const onSubmit = async (values: ChangePasswordFormValues) => {
    setServerError(null)
    setSuccessNotice(false)

    try {
      await changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      })
      showToast('success', 'Password Changed', 'Your administrator password has been updated.')
      setSuccessNotice(true)
      reset()
    } catch (err) {
      if (err instanceof NormalizedApiError) {
        setServerError(err.message || 'Failed to update password. Please check credentials.')
      } else {
        setServerError('An unexpected error occurred while communicating with the authentication service.')
      }
    }
  }

  const formatRole = (role: string | undefined) => {
    if (!role) return 'Standard'
    return role.replace('ROLE_', '').replace('_', ' ')
  }

  return (
    <div className="space-y-6 max-w-4xl">
      {/* Header */}
      <div className="pb-3 border-b border-moss/40">
        <h1 className="text-2xl font-bold font-mono tracking-tight text-silver-frost">Security & Account Settings</h1>
        <p className="text-xs font-mono text-titanium mt-1">
          Administrator identity details, session isolation, and cryptographic credentials
        </p>
      </div>

      {/* Mandatory Password Change Alert if applicable */}
      {admin?.mustChangePassword && (
        <div className="p-4 bg-warning/10 border border-warning/30 rounded-xl text-xs font-mono text-warning flex items-start gap-3 shadow-xs">
          <AlertTriangle className="w-5 h-5 text-warning shrink-0 mt-0.5" />
          <div className="space-y-1">
            <h4 className="font-bold text-silver-frost">Action Required: Change Initial Password</h4>
            <p className="leading-relaxed text-titanium">
              Your account is configured with a temporary bootstrap password. You must establish a secure new password
              (minimum 12 characters) before accessing other platform management functions.
            </p>
          </div>
        </div>
      )}

      {/* Success Notification */}
      {successNotice && (
        <div className="p-4 bg-emerald/10 border border-emerald/30 rounded-xl text-xs font-mono text-emerald flex items-center gap-2.5">
          <CheckCircle2 className="w-5 h-5 text-emerald shrink-0" />
          <span>Password changed successfully. Your in-memory session token has been renewed.</span>
        </div>
      )}

      {/* Profile Overview Card */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base font-mono flex items-center gap-2 text-silver-frost">
            <User className="w-4 h-4 text-emerald" />
            Administrator Profile
          </CardTitle>
          <CardDescription className="text-titanium font-mono">Authenticated identity metadata authenticated by Gatekeeper</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
            <div className="p-3.5 bg-canvas border border-moss/20 rounded-lg">
              <span className="font-semibold text-titanium uppercase tracking-wider text-[10px] font-mono">Username</span>
              <div className="text-sm font-bold font-mono text-silver-frost mt-1">{admin?.username}</div>
            </div>

            <div className="p-3.5 bg-canvas border border-moss/20 rounded-lg">
              <span className="font-semibold text-titanium uppercase tracking-wider text-[10px] font-mono">Contact Email</span>
              <div className="text-sm font-mono text-silver-frost mt-1">{admin?.email}</div>
            </div>

            <div className="p-3.5 bg-canvas border border-moss/20 rounded-lg">
              <span className="font-semibold text-titanium uppercase tracking-wider text-[10px] font-mono">Role Privilege</span>
              <div className="mt-1">
                <Badge variant="purple">{formatRole(admin?.role)}</Badge>
              </div>
            </div>

            <div className="p-3.5 bg-canvas border border-moss/20 rounded-lg">
              <span className="font-semibold text-titanium uppercase tracking-wider text-[10px] font-mono">Admin Identifier</span>
              <div className="text-xs font-mono text-titanium mt-1 truncate">{admin?.id}</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Change Password Card */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base font-mono flex items-center gap-2 text-silver-frost">
            <Lock className="w-4 h-4 text-emerald" />
            Change Administrator Password
          </CardTitle>
          <CardDescription className="text-titanium font-mono">
            Updates your credentials via <code className="text-silver-frost">POST /api/v1/auth/change-password</code>
          </CardDescription>
        </CardHeader>
        <CardContent>
          {serverError && (
            <div className="mb-4 p-3 bg-signal-red/10 border border-signal-red/30 rounded-lg text-xs font-mono text-signal-red flex items-start gap-2">
              <ShieldAlert className="w-4 h-4 text-signal-red shrink-0 mt-0.5" />
              <span>{serverError}</span>
            </div>
          )}

          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4 max-w-md">
            <Input
              label="Current Password"
              type="password"
              placeholder="••••••••••••"
              autoComplete="current-password"
              {...register('oldPassword')}
              error={errors.oldPassword?.message}
            />

            <Input
              label="New Password (min 12 characters)"
              type="password"
              placeholder="••••••••••••"
              autoComplete="new-password"
              {...register('newPassword')}
              error={errors.newPassword?.message}
            />

            <Input
              label="Confirm New Password"
              type="password"
              placeholder="••••••••••••"
              autoComplete="new-password"
              {...register('confirmPassword')}
              error={errors.confirmPassword?.message}
            />

            <div className="pt-2">
              <Button type="submit" variant="primary" isLoading={isSubmitting}>
                Update Password
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      {/* Security Architecture Notice */}
      <div className="p-4 bg-surface border border-moss/30 rounded-xl text-xs font-mono text-titanium leading-relaxed flex items-start gap-3">
        <Shield className="w-5 h-5 text-emerald shrink-0 mt-0.5" />
        <div>
          <strong className="text-silver-frost font-bold">Token Isolation:</strong> Session tokens are held strictly in memory and
          are destroyed upon tab closure or page reload. No tokens, secrets, or passwords are ever stored in
          localStorage, sessionStorage, or browser cookies.
        </div>
      </div>
    </div>
  )
}
