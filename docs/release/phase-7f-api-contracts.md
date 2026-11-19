# Gatekeeper — Phase 7F: API Contract Parity & Backend DTO Alignment

**Inspection Target:** Frontend API services vs. Backend Spring Boot 3.3 Controllers & DTOs  
**Status:** 100% Contractual Alignment Confirmed  

---

## 1. Controller and Service Parity Matrix

| Domain | Frontend Service | Backend Controller | HTTP Method & Path | Request / Response Contract |
| :--- | :--- | :--- | :--- | :--- |
| **Auth** | `authService.ts` | `AuthController.java` | `POST /api/v1/auth/login` | `LoginRequest` -> `LoginResponse` (returns JWT + `AdminProfileDto`). |
| **Auth** | `authService.ts` | `AuthController.java` | `POST /api/v1/auth/change-password` | `ChangePasswordRequest` -> `LoginResponse`. |
| **Consumers** | `consumerService.ts` | `ConsumerController.java` | `GET /api/v1/consumers` | Returns `PageResponse<ConsumerDto>`. |
| **Consumers** | `consumerService.ts` | `ConsumerController.java` | `POST /api/v1/consumers` | `CreateConsumerRequest` -> `ConsumerDto`. |
| **Consumers** | `consumerService.ts` | `ConsumerController.java` | `PATCH /api/v1/consumers/{id}/plan` | `UpdatePlanRequest` -> `ConsumerDto`. |
| **Consumers** | `consumerService.ts` | `ConsumerController.java` | `PATCH /api/v1/consumers/{id}/suspend` | Suspends consumer state. |
| **Consumers** | `consumerService.ts` | `ConsumerController.java` | `PATCH /api/v1/consumers/{id}/reactivate`| Reactivates consumer state. |
| **API Keys** | `apiKeyService.ts` | `ApiKeyController.java` | `GET /api/v1/consumers/{id}/keys` | Returns `List<ApiKeyDto>`. |
| **API Keys** | `apiKeyService.ts` | `ApiKeyController.java` | `POST /api/v1/consumers/{id}/keys` | `CreateApiKeyRequest` -> `ApiKeyCreatedResponse` (one-time secret). |
| **API Keys** | `apiKeyService.ts` | `ApiKeyController.java` | `POST /api/v1/consumers/{id}/keys/{kId}/revoke` | `RevokeKeyRequest` -> `ApiKeyDto`. |
| **Plans** | `planService.ts` | `PlanController.java` | `GET /api/v1/plans` | Returns `List<PlanDto>` (token bucket configurations). |
| **Analytics** | `analyticsService.ts` | `AnalyticsController.java` | `GET /api/v1/analytics/overview` | Returns `OverviewMetricsDto` (total, allowed, rate limited, p95/p99). |
| **Analytics** | `analyticsService.ts` | `AnalyticsController.java` | `GET /api/v1/analytics/timeseries` | Accepts `sinceHours` / `bucketMinutes` -> `List<TimeSeriesPointDto>`. |
| **Analytics** | `analyticsService.ts` | `AnalyticsController.java` | `GET /api/v1/analytics/top-consumers` | Accepts `limit` -> `List<TopConsumerUsageDto>`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `GET /api/v1/policies` | Returns `List<PolicyDefinitionDto>`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `GET /api/v1/policies/{key}/versions` | Returns `List<PolicyVersionDto>`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `POST /api/v1/policies/{key}/versions` | `CreatePolicyVersionRequest` -> `PolicyVersionDto`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `POST /api/v1/policies/{key}/simulate`| `PolicySimulationRequest` -> `PolicySimulationResponse`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `POST /api/v1/policies/{key}/replay` | `PolicyReplayRequest` -> `PolicyReplayResponse`. |
| **Policies** | `policyService.ts` | `PolicyAdminController.java` | `POST /api/v1/policies/{key}/versions/{v}/activate` | Activates policy version and generates audit entry. |
| **Resilience**| `resilienceService.ts` | `ResilienceAdminController.java` | `GET /api/v1/resilience/routes` | Returns `List<RouteResilienceSummary>`. |
| **Resilience**| `resilienceService.ts` | `ResilienceAdminController.java` | `GET /api/v1/resilience/routes/{id}` | Returns `RouteResilienceDetail`. |
| **Resilience**| `resilienceService.ts` | `ResilienceAdminController.java` | `POST /api/v1/resilience/routes/{id}/reset` | Resets circuit breaker to `CLOSED` state. |
| **Chaos** | `chaosService.ts` | `ChaosAdminController.java` | `GET /api/v1/chaos/experiments` | Returns `List<ChaosExperiment>`. |
| **Chaos** | `chaosService.ts` | `ChaosAdminController.java` | `POST /api/v1/chaos/experiments/run` | `ChaosRunRequest` -> `ChaosExperiment` (locked in prod). |
| **Health** | `healthService.ts` | `HealthController.java` | `GET /api/v1/health` | Probes system status, Redis readiness, and Postgres state. |

---

## 2. Key Data Normalization & Handling

1. **Pagination Wrapping:** Backend endpoints returning Spring `Page<T>` (e.g. `/api/v1/consumers`) deliver payload structures containing `{ content: T[], totalPages: number, totalElements: number }`. All frontend consumers unwrap `.content` defensively.
2. **Defensive Array Fallbacks:** All array mapping expressions in views (`definitions.map`, `versions.map`, `topConsumers.map`) are preceded by explicit `Array.isArray()` checks to prevent crashes when responses arrive empty or malformed.
3. **Date Formats:** Timestamps exchange as ISO 8601 UTC strings (`YYYY-MM-DDTHH:mm:ssZ`) and are formatted into locale strings in the UI.
