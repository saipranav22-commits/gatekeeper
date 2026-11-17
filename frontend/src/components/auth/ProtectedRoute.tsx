import React from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { AdminRole } from '@/types/auth'
import { ShieldX } from 'lucide-react'
import { Button } from '@/components/ui/Button'

interface ProtectedRouteProps {
  children: React.ReactNode
  requiredRoles?: AdminRole | AdminRole[]
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ children, requiredRoles }) => {
  const { isAuthenticated, admin, hasRole } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    // Redirect unauthenticated user to login while saving target location
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  // Force password change workflow if required by backend
  if (admin?.mustChangePassword && location.pathname !== '/security') {
    return <Navigate to="/security" state={{ mustChangePasswordNotice: true }} replace />
  }

  // Role authorization check
  if (requiredRoles && !hasRole(requiredRoles)) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] p-8 text-center">
        <div className="p-4 bg-rose-50 border border-rose-200 rounded-full mb-4">
          <ShieldX className="w-10 h-10 text-rose-600" />
        </div>
        <h2 className="text-xl font-bold text-slate-900 mb-2">Access Restricted</h2>
        <p className="text-sm text-slate-500 max-w-md mb-6">
          Your role ({admin?.role}) does not have permission to access this administrative area.
        </p>
        <Button variant="outline" onClick={() => window.history.back()}>
          Go Back
        </Button>
      </div>
    )
  }

  return <>{children}</>
}
