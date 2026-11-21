import { HealthResponse } from '@/types/health'
import { ActuatorHealthResponse } from '@/services/healthService'

export const demoHealthService = {
  async getHealth(): Promise<HealthResponse> {
    return {
      status: 'UP',
      service: 'gatekeeper-gateway',
      serviceName: 'Gatekeeper Enterprise Gateway (Demo Model)',
      timestamp: new Date().toISOString(),
    }
  },

  async getActuatorHealth(): Promise<ActuatorHealthResponse> {
    return {
      status: 'UP',
      components: {
        db: {
          status: 'UP',
          details: {
            database: 'PostgreSQL 16 (Demonstration Model)',
            validationQuery: 'isValid()',
          },
        },
        redis: {
          status: 'UP',
          details: {
            version: '7.2.4 (Demonstration Model)',
          },
        },
        diskSpace: {
          status: 'UP',
          details: {
            total: 10737418240,
            free: 8589934592,
            threshold: 10485760,
          },
        },
        ping: {
          status: 'UP',
        },
      },
    }
  },
}
