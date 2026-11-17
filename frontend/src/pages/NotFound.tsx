import React from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import { AlertCircle, Home } from 'lucide-react'

export const NotFound: React.FC = () => {
  return (
    <div className="min-h-[70vh] flex flex-col items-center justify-center p-6 text-center">
      <div className="w-16 h-16 rounded-2xl bg-slate-100 flex items-center justify-center text-slate-500 mb-4 border border-slate-200">
        <AlertCircle className="w-8 h-8 text-slate-400" />
      </div>
      <h1 className="text-3xl font-bold text-slate-900 tracking-tight">404</h1>
      <h2 className="text-base font-semibold text-slate-700 mt-1">Page Not Found</h2>
      <p className="text-xs text-slate-500 max-w-sm mt-2 mb-6">
        The route you are trying to access does not exist on the Gatekeeper administrative console.
      </p>
      <Link to="/">
        <Button variant="primary" size="md">
          <Home className="w-4 h-4 mr-2" />
          Back to Overview
        </Button>
      </Link>
    </div>
  )
}
