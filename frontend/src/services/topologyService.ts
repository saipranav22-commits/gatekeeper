import {
  ConfiguredTopologyData,
  ServiceTopologyLink,
  ServiceTopologyNode,
  CircuitBreakerState,
} from '@/types/resilience'
import { RouteResilienceSummary } from '@/types/health'
import { ActuatorHealthResponse } from './healthService'

export const topologyService = {
  /**
   * Constructs the Configured Service Topology based on actual Spring Boot routes
   * and live health/resilience data.
   *
   * Invariants:
   * - Labeled explicitly as "configured" (isDiscovered: false).
   * - Shows UNKNOWN for database/cache if actuator component health is unavailable.
   * - Derives upstream service health strictly from circuit breaker state and failure rate.
   */
  buildTopology(
    gatewayHealthStatus: string,
    routesResilience: RouteResilienceSummary[] | null,
    actuatorHealth: ActuatorHealthResponse | null,
    lastObservedTimestamp?: string
  ): ConfiguredTopologyData {
    const timestamp = lastObservedTimestamp || new Date().toISOString()

    // 1. Map route resilience summaries
    const ordersResilience = routesResilience?.find((r) => r.routeId === 'orders-service-v1')
    const inventoryResilience = routesResilience?.find((r) => r.routeId === 'inventory-service-v1')

    // 2. Derive Upstream Orders Status
    let ordersStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' = 'UNKNOWN'
    let ordersStatusReason = 'No telemetry observed yet for orders-service-v1'
    if (ordersResilience) {
      if (ordersResilience.circuitBreakerState === 'OPEN') {
        ordersStatus = 'DOWN'
        ordersStatusReason = `Circuit Breaker is OPEN (${ordersResilience.failureRate.toFixed(1)}% failure rate, ${ordersResilience.rejectedCalls} rejected)`
      } else if (ordersResilience.circuitBreakerState === 'HALF_OPEN') {
        ordersStatus = 'DEGRADED'
        ordersStatusReason = 'Circuit Breaker is HALF_OPEN (probing upstream recovery)'
      } else if (ordersResilience.failureRate > 20 || ordersResilience.slowCallRate > 30) {
        ordersStatus = 'DEGRADED'
        ordersStatusReason = `Elevated error/latency rate: ${ordersResilience.failureRate.toFixed(1)}% fail, ${ordersResilience.slowCallRate.toFixed(1)}% slow`
      } else {
        ordersStatus = 'HEALTHY'
        ordersStatusReason = `Circuit Breaker CLOSED (${ordersResilience.totalCalls} calls handled, ${ordersResilience.failureRate.toFixed(1)}% error)`
      }
    }

    // 3. Derive Upstream Inventory Status
    let inventoryStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' = 'UNKNOWN'
    let inventoryStatusReason = 'No telemetry observed yet for inventory-service-v1'
    if (inventoryResilience) {
      if (inventoryResilience.circuitBreakerState === 'OPEN') {
        inventoryStatus = 'DOWN'
        inventoryStatusReason = `Circuit Breaker is OPEN (${inventoryResilience.failureRate.toFixed(1)}% failure rate)`
      } else if (inventoryResilience.circuitBreakerState === 'HALF_OPEN') {
        inventoryStatus = 'DEGRADED'
        inventoryStatusReason = 'Circuit Breaker is HALF_OPEN (probing upstream recovery)'
      } else {
        inventoryStatus = 'HEALTHY'
        inventoryStatusReason = `Circuit Breaker CLOSED (${inventoryResilience.totalCalls} calls handled)`
      }
    }

    // 4. Derive Database Status from Actuator Health
    let dbStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' = 'UNKNOWN'
    let dbReason = 'Actuator component health not reported'
    if (actuatorHealth?.components?.db) {
      const dbComponent = actuatorHealth.components.db
      if (dbComponent.status === 'UP') {
        dbStatus = 'HEALTHY'
        dbReason = 'HikariCP connection pool active and validated'
      } else if (dbComponent.status === 'DOWN') {
        dbStatus = 'DOWN'
        dbReason = 'Database connection failure reported by Actuator'
      } else {
        dbStatus = 'DEGRADED'
        dbReason = `Actuator status: ${dbComponent.status}`
      }
    }

    // 5. Derive Redis Status from Actuator Health
    let redisStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' = 'UNKNOWN'
    let redisReason = 'Actuator component health not reported'
    if (actuatorHealth?.components?.redis) {
      const redisComponent = actuatorHealth.components.redis
      if (redisComponent.status === 'UP') {
        redisStatus = 'HEALTHY'
        redisReason = 'Redis client connected (Token Bucket Lua & Login Rate Limiter active)'
      } else if (redisComponent.status === 'DOWN') {
        redisStatus = 'DOWN'
        redisReason = 'Redis connectivity severed — failing closed safely'
      } else {
        redisStatus = 'DEGRADED'
        redisReason = `Actuator status: ${redisComponent.status}`
      }
    }

    // 6. Gateway Core Status
    let gwStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' = 'UNKNOWN'
    let gwReason = 'Checking gateway connectivity...'
    if (gatewayHealthStatus === 'HEALTHY') {
      gwStatus = 'HEALTHY'
      gwReason = 'Gateway process responsive, filters active, routing operational'
    } else if (gatewayHealthStatus === 'DEGRADED') {
      gwStatus = 'DEGRADED'
      gwReason = 'Gateway process responsive, but downstream dependencies or breakers are degraded'
    } else if (gatewayHealthStatus === 'OFFLINE') {
      gwStatus = 'DOWN'
      gwReason = 'Gateway unresponsive to HTTP probes'
    }

    const nodes: ServiceTopologyNode[] = [
      {
        id: 'client-ingress',
        label: 'Client Ingress',
        serviceId: 'public-clients',
        type: 'CLIENT',
        role: 'Public Consumers & API Key Traffic',
        endpointOrHost: 'https://api.gatekeeper.local',
        status: 'HEALTHY',
        statusReason: 'Client traffic ingress active',
        lastObserved: timestamp,
      },
      {
        id: 'gateway-core',
        label: 'Gatekeeper Gateway',
        serviceId: 'gatekeeper-core',
        type: 'GATEWAY',
        role: 'Reverse Proxy, SSRF Guard, Rate Limiter & Resilience Plane',
        endpointOrHost: ':8080',
        status: gwStatus,
        statusReason: gwReason,
        lastObserved: timestamp,
      },
      {
        id: 'orders-service',
        label: 'Orders Service',
        serviceId: 'orders-service',
        type: 'UPSTREAM_HTTP',
        role: 'Order Processing & Transactional Store',
        endpointOrHost: 'http://orders:8081',
        status: ordersStatus,
        statusReason: ordersStatusReason,
        lastObserved: timestamp,
        circuitBreakerState: ordersResilience?.circuitBreakerState as CircuitBreakerState,
        failureRate: ordersResilience?.failureRate,
        inFlightCalls: ordersResilience?.bulkheadInFlightCalls,
        maxConcurrency: ordersResilience?.bulkheadMaxConcurrent,
        metrics: {
          totalRequests: ordersResilience?.totalCalls,
          errorRatePercent: ordersResilience?.failureRate,
        },
      },
      {
        id: 'inventory-service',
        label: 'Inventory Service',
        serviceId: 'inventory-service',
        type: 'UPSTREAM_HTTP',
        role: 'Catalog & Inventory Reservation',
        endpointOrHost: 'http://inventory:8082',
        status: inventoryStatus,
        statusReason: inventoryStatusReason,
        lastObserved: timestamp,
        circuitBreakerState: inventoryResilience?.circuitBreakerState as CircuitBreakerState,
        failureRate: inventoryResilience?.failureRate,
        inFlightCalls: inventoryResilience?.bulkheadInFlightCalls,
        maxConcurrency: inventoryResilience?.bulkheadMaxConcurrent,
        metrics: {
          totalRequests: inventoryResilience?.totalCalls,
          errorRatePercent: inventoryResilience?.failureRate,
        },
      },
      {
        id: 'postgres-db',
        label: 'PostgreSQL 16',
        serviceId: 'postgres-primary',
        type: 'DATABASE',
        role: 'Relational Store (Consumers, API Keys, Policies, Audit Logs)',
        endpointOrHost: '5434 / gatekeeper',
        status: dbStatus,
        statusReason: dbReason,
        lastObserved: timestamp,
      },
      {
        id: 'redis-cache',
        label: 'Redis 7 Engine',
        serviceId: 'redis-token-bucket',
        type: 'CACHE',
        role: 'Atomic Lua Token Bucket, Login Protection & Rate Limiting',
        endpointOrHost: '6380 / redis',
        status: redisStatus,
        statusReason: redisReason,
        lastObserved: timestamp,
      },
    ]

    const links: ServiceTopologyLink[] = [
      {
        source: 'client-ingress',
        target: 'gateway-core',
        label: 'HTTPS / API Key Auth',
        protocol: 'HTTPS',
        isEncrypted: true,
        isRateLimited: true,
      },
      {
        source: 'gateway-core',
        target: 'orders-service',
        label: '/api/v1/proxy/orders/**',
        protocol: 'HTTP',
        circuitState: ordersResilience?.circuitBreakerState as CircuitBreakerState,
        isEncrypted: false,
        isRateLimited: true,
      },
      {
        source: 'gateway-core',
        target: 'inventory-service',
        label: '/api/v1/proxy/inventory/**',
        protocol: 'HTTP',
        circuitState: inventoryResilience?.circuitBreakerState as CircuitBreakerState,
        isEncrypted: false,
        isRateLimited: true,
      },
      {
        source: 'gateway-core',
        target: 'postgres-db',
        label: 'HikariCP / JDBC',
        protocol: 'JDBC',
        isEncrypted: false,
        isRateLimited: false,
      },
      {
        source: 'gateway-core',
        target: 'redis-cache',
        label: 'Lettuce / EVALSHA',
        protocol: 'REDIS',
        isEncrypted: false,
        isRateLimited: false,
      },
    ]

    return {
      nodes,
      links,
      generatedAt: timestamp,
      isDiscovered: false,
    }
  },
}
