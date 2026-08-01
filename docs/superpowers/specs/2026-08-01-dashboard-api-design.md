# Dashboard API — Design Spec

Date: 2026-08-01

## Goal

Back the existing (currently 100%-mock) dashboard UI with real data from the Flowmatic backend. New package `com.flowmatic.auth.workflow.dashboard`, scoped to the caller's own workflows, following existing project conventions (JWT auth via `Authentication`, manual ownership checks, no `@PreAuthorize`, no `ApiResponse<T>` wrapper).

Frontend wiring (swapping the mock data in `/Users/swym/Developer/my-app/components/dashboard/*` for real fetches) is explicitly **out of scope** for this package — this spec covers the backend API only.

## Scope decisions (confirmed with user)

1. **Trigger type**: no schedule/webhook trigger path exists today — every run is manual. v1 adds `TriggerType{MANUAL}` and stamps every run `MANUAL` at the one real entry point (`WorkflowExecutionService.enqueue()`). Building real scheduled/webhook triggering is explicitly deferred to a future feature.
2. **Error cause**: the executor only does generic `catch (Exception)`/`catch (RuntimeException)` today — no typed exceptions exist. v1 adds a lightweight heuristic classifier (`ErrorCause{TIMEOUT, NODE_FAILURE, VALIDATION, UNKNOWN}`) in the executor's existing catch blocks, based on exception type and where in the pipeline the failure occurred. No new exception types added elsewhere.
3. **Workflow pause**: `Workflow.status` (`ACTIVE`/`PAUSED`) doesn't exist today. v1 adds the field plus a manual activate/pause endpoint that logs to the activity feed. Automatic pause-after-N-failures logic is explicitly deferred.

## Schema changes (Hibernate `ddl-auto=update`, MySQL — no Flyway)

| Entity | Change |
|---|---|
| `WorkflowRun` | `+ triggerType TriggerType` (`@Enumerated(STRING)`, new enum, single value `MANUAL` for now). `+ errorCause ErrorCause` (`@Enumerated(STRING)`, nullable, new enum: `TIMEOUT, NODE_FAILURE, VALIDATION, UNKNOWN`). Duration is **computed** from `completedAt - startedAt`, not a stored column. |
| `Workflow` | `+ status WorkflowStatus` (`@Enumerated(STRING)`, new enum `ACTIVE, PAUSED`, default `ACTIVE`) |
| new `WorkflowActivityLog` | `id, workflow (FK, @ManyToOne LAZY), actorName (String), actionType (ACTIVATED/PAUSED), message (String), createdAt (Instant, @CreationTimestamp)` |

## Stamping points

- `WorkflowExecutionService.enqueue()` — sets `triggerType = MANUAL` when building the `WorkflowRun`.
- `WorkflowExecutionService.java:214-217` (per-node catch) and `:235-238` (whole-run catch) — add a `classifyError(Throwable)` helper: `SocketTimeoutException`/`TimeoutException` → `TIMEOUT`; a node-level throw → `NODE_FAILURE`; graph-parsing failures (existing `IllegalArgumentException` at `:290-292`) → `VALIDATION`; else `UNKNOWN`.
- New `PUT /api/workflows/{id}/status` on the existing `WorkflowController` (body `{status: "ACTIVE"|"PAUSED"}`), writes a `WorkflowActivityLog` row with `actorName` = current user's email.

## Endpoints

All under `/api/dashboard`, JWT-secured, scoped via `currentUser.requireUserId(authentication)` like every other controller in the codebase.

| Endpoint | Query params | Powers |
|---|---|---|
| `GET /api/dashboard/overview` | `days` (default 30) | Bundles all of the below, for initial page load |
| `GET /api/dashboard/summary` | — | 4 KPI cards |
| `GET /api/dashboard/executions-over-time` | `days` (default 30) | Area chart |
| `GET /api/dashboard/executions-by-trigger` | `days` (default 7) | Donut chart |
| `GET /api/dashboard/failures-by-cause` | `days` (default 10) | Stacked bar |
| `GET /api/dashboard/median-run-duration` | `days` (default 7) | Line chart |
| `GET /api/dashboard/busiest-workflows` | `days` (default 30), `limit` (default 5) | List |
| `GET /api/dashboard/recent-executions` | `limit` (default 10) | Table |
| `GET /api/dashboard/activity` | `limit` (default 10) | Activity feed |

## DTOs (`dashboard/dto/`, Java records — matches existing request-DTO convention)

- `SummaryStatsDTO{executionsToday, executionsTodayDeltaPct, successRatePct, successRateDeltaPp, failedRuns, failedRunsDeltaPct, medianRunTimeSec, medianRunTimeDeltaPct}`
- `TimeSeriesPointDTO{date, count}` + `ExecutionsOverTimeDTO{points, deltaPct}`
- `TriggerBreakdownDTO{manualPct, schedulePct, webhookPct, deltaPp}` (schedule/webhook read 0 until that feature exists)
- `FailureCauseBucketDTO{date, cause, count}` (flat list; service aggregates per date × cause)
- `DurationTrendDTO{dayLabel, medianSec}` + overall `deltaPct`
- `BusiestWorkflowDTO{workflowId, name, initials, active, runCount}` (`initials` = first letter of up to the first two words in `name`, uppercased)
- `RecentExecutionDTO{workflowName, lastNode, triggerType, durationMs, status}` (uses real `WorkflowRunStatus` values; reconciling to the frontend's display vocabulary is a future frontend-adapter concern)
- `ActivityItemDTO{type, message, actorName, timestamp}`

## Query & aggregation approach

MySQL has no `PERCENTILE_CONT`/`date_trunc`, and the codebase's existing `@Query` usages are all JPQL (no native SQL precedent). So: fetch bounded raw rows for the requested day range via JPQL (`WHERE workflow.user.id = :userId AND startedAt >= :since`), then bucket-by-day and compute medians in Java (sort durations, take the middle value(s)). This stays portable to H2 for tests and doesn't introduce the first native/dialect-specific query in the repo.

## Deferred (YAGNI)

Caffeine caching and a `run_daily_stats` rollup table — the original plan flagged these as needed "once you're past a few hundred thousand runs," which doesn't apply yet. Both can be layered in later behind the same `DashboardService` interface without changing the controller or DTO contracts.

## Definitions

- "Today" / day boundaries use the UTC calendar day (all timestamps in the domain are `Instant`; no user-timezone concept exists anywhere in the codebase today).
- `successRatePct` is computed over **completed** runs only (`SUCCESS` + `FAILED`) in the window — `PENDING`/`RUNNING` runs are excluded from the denominator since they haven't reached a terminal state.

## Edge cases

- New user / workflow with zero runs → every endpoint returns zero-valued or empty DTOs, never an error.
- Delta-vs-previous-period math: previous period had 0 executions → delta is `null`, not `NaN`/`Infinity`.

## Testing

Integration tests under the `test` profile (H2), seeding `Workflow`/`WorkflowRun` rows directly through repositories in `@BeforeEach`, asserting JSON via `MockMvc` — same pattern as the existing workflow/run tests.

## Explicitly out of scope for this spec

- Real scheduled/webhook triggering (§ scope decision 1)
- Typed exceptions across node executors (§ scope decision 2)
- Automatic pause-after-N-failures (§ scope decision 3)
- Frontend data-fetching layer / adapting DTOs to existing chart component prop shapes
- Caching and rollup tables (§ Deferred)
