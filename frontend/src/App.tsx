import React, { Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'
import { GatewayHealthProvider } from '@/context/GatewayHealthContext'
import { ProtectedRoute } from '@/components/auth/ProtectedRoute'
import { AppLayout } from '@/components/layout/AppLayout'

// Lazy-loaded pages for code-splitting and bundle optimization
const Login = React.lazy(() => import('@/pages/Login').then((m) => ({ default: m.Login })))
const Overview = React.lazy(() => import('@/pages/Overview').then((m) => ({ default: m.Overview })))
const Consumers = React.lazy(() => import('@/pages/Consumers').then((m) => ({ default: m.Consumers })))
const ConsumerDetail = React.lazy(() => import('@/pages/ConsumerDetail').then((m) => ({ default: m.ConsumerDetail })))
const ApiKeys = React.lazy(() => import('@/pages/ApiKeys').then((m) => ({ default: m.ApiKeys })))
const Plans = React.lazy(() => import('@/pages/Plans').then((m) => ({ default: m.Plans })))
const PolicyStudio = React.lazy(() => import('@/pages/PolicyStudio').then((m) => ({ default: m.PolicyStudio })))
const ReliabilityWorkbench = React.lazy(() => import('@/pages/ReliabilityWorkbench').then((m) => ({ default: m.ReliabilityWorkbench })))
const ChaosLab = React.lazy(() => import('@/pages/ChaosLab').then((m) => ({ default: m.ChaosLab })))
const Analytics = React.lazy(() => import('@/pages/Analytics').then((m) => ({ default: m.Analytics })))
const Security = React.lazy(() => import('@/pages/Security').then((m) => ({ default: m.Security })))
const NotFound = React.lazy(() => import('@/pages/NotFound').then((m) => ({ default: m.NotFound })))

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

const RouteFallback: React.FC = () => (
  <div className="p-8 space-y-4">
    <div className="flex items-center gap-3">
      <div className="w-4 h-4 rounded-full border-2 border-emerald border-t-transparent animate-spin" />
      <span className="text-xs font-mono text-titanium">Loading gateway console...</span>
    </div>
    <div className="h-24 w-full bg-surface-elevated/50 border border-moss/30 rounded-xl animate-pulse" />
    <div className="h-64 w-full bg-surface-elevated/30 border border-moss/20 rounded-xl animate-pulse" />
  </div>
)

export const App: React.FC = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <GatewayHealthProvider>
          <ToastProvider>
            <BrowserRouter>
              <Suspense fallback={<RouteFallback />}>
                <Routes>
                  {/* Public Routes */}
                  <Route path="/login" element={<Login />} />

                  {/* Protected Platform Routes */}
                  <Route
                    element={
                      <ProtectedRoute>
                        <AppLayout />
                      </ProtectedRoute>
                    }
                  >
                    <Route path="/" element={<Overview />} />
                    <Route path="/consumers" element={<Consumers />} />
                    <Route path="/consumers/:consumerId" element={<ConsumerDetail />} />
                    <Route path="/api-keys" element={<ApiKeys />} />
                    <Route path="/plans" element={<Plans />} />
                    <Route path="/policies" element={<PolicyStudio />} />
                    <Route path="/reliability" element={<ReliabilityWorkbench />} />
                    <Route path="/chaos" element={<ChaosLab />} />
                    <Route path="/analytics" element={<Analytics />} />
                    <Route path="/security" element={<Security />} />
                    <Route path="/404" element={<NotFound />} />
                    <Route path="*" element={<Navigate to="/404" replace />} />
                  </Route>
                </Routes>
              </Suspense>
            </BrowserRouter>
          </ToastProvider>
        </GatewayHealthProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}

export default App
