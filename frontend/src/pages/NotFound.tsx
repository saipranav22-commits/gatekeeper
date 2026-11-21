import React from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import { AlertCircle, Home } from 'lucide-react'

export const NotFound: React.FC = () => {
  return (
    <div className="min-h-[70vh] flex flex-col items-center justify-center p-6 text-center bg-canvas">
      <div className="w-16 h-16 rounded-2xl bg-surface flex items-center justify-center text-warning mb-4 border border-moss/30">
        <AlertCircle className="w-8 h-8 text-warning" />
      </div>
      <h1 className="text-4xl font-bold font-mono text-silver-frost tracking-tight">404</h1>
      <h2 className="text-base font-semibold font-mono text-emerald mt-1">Route Not Found</h2>
      <p className="text-xs font-mono text-titanium max-w-sm mt-2 mb-6">
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
