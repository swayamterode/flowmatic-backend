# Dashboard API — Design Spec (v2, trimmed)

Date: 2026-08-08

Supersedes: `docs/superpowers/specs/2026-08-01-dashboard-api-design.md` (never implemented — no
`dashboard` package exists in the codebase yet). That spec covered a larger page (this one, plus a
busiest-workflows list, recent-executions table, activity feed, and a `Workflow.status`
activate/pause endpoint). This version scopes down to exactly what the real mockup screenshots show:
4 KPI cards + 4 charts, nothing below the fold.

## Goal

Back the existing (currently 100%-mock) dashboard UI with real data from the Flowmatic backend. New
package `com.flowmatic.auth.workflow.dashboard`, scoped to the caller's own workflows, following
existing project conventions (JWT auth via `Authentication`, manual ownership checks via
`currentUser.requireUserId(authentication)`, no `@PreAuthorize`, no `ApiResponse<T>` wrapper).

Frontend wiring (swapping the mock data for real fetches) is out of scope — backend API only,
confirmed with user.

## Scope decisions (confirmed with user)

1. **Trigger type**: no schedule/webhook trigger path exists today — every run is manual. v1 adds
   `TriggerType{MANUAL}` and stamps every run `MANUAL` at the one real entry point
   (`WorkflowExecutionService.enqueue()`). The "Executions by trigger" donut will honestly show 100%
   Manual (Schedule/Webhook at 0%) until real scheduled/webhook triggering is built — user confirmed
   this is fine rather than hiding the widget or building real triggering now.
2. **Error cause**: every `NodeExecutor` (`DataSourceNodeExecutor`, `HttpNodeExecutor`, etc.) already
   catches its own exceptions internally and returns `NodeExecutionResult.failure("some message")` —
   none let the original exception type escape. E.g. `IntegrationNotConnectedException` is thrown
   inside `UserIntegrationService`, but `DataSourceNodeExecutor.fromDrive()` catches it as a plain
   `RuntimeException` and flattens it to the string `"Drive source failed: Google Drive is not
   connected"` before `WorkflowExecutionService` ever sees it. So classifying by exception type at
   the `WorkflowExecutionService` catch sites (as the superseded spec did) doesn't actually work for
   anything other than the one case where the real exception genuinely propagates untouched: bad
   `graph_json` (`parseGraph()`'s `IllegalArgumentException`, thrown before any executor runs).

   v1 adds `ErrorCause{TIMEOUT, AUTH, VALIDATION, OTHER}`, classified by a `classifyError(String
   errorMessage)` helper applied to the **final composed error message** (whatever ends up in
   `composeError(result)` for a per-node failure, or `e.getMessage()` for the rare outer-catch
   failure) — confirmed with user, no executor changes needed:
   - `IllegalArgumentException` caught directly in the outer catch (bad graph JSON) → `VALIDATION`
   - message contains "not connected" or "reconnect" → `AUTH`
   - message contains "timeout" or "timed out" → `TIMEOUT`
   - anything else → `OTHER`

   This is a heuristic over human-readable strings, same spirit as the superseded spec's own
   timeout-by-message-substring check — just applied consistently since that's genuinely the only
   signal left by the time an error reaches `WorkflowExecutionService`.

   Note: this does **not** change `HttpNodeExecutor` behavior — a non-2xx HTTP response from an HTTP
   node remains a successful node execution with a numeric `status` field (per its existing doc
   comment), not a failure. The mockup's "HTTP 5xx" label doesn't have a real backend equivalent
   today; user confirmed relabeling to the categories above (rather than changing HTTP node
   semantics) is the right call for v1.
3. **Out of scope for this page** (were in the superseded 2026-08-01 spec, dropped here since the
   real mockup doesn't show them): `Workflow.status` (activate/pause), `WorkflowActivityLog`,
   busiest-workflows list, recent-executions table, activity feed. If a future page needs these,
   revive the relevant tasks from the superseded spec/plan rather than redesigning from scratch.

## Schema changes (Hibernate `ddl-auto=update`, MySQL — no Flyway)

| Entity | Change |
|---|---|
| `WorkflowRun` | `+ triggerType TriggerType` (`@Enumerated(STRING)`, new enum, single value `MANUAL` for now, nullable at the DB level). `+ errorCause ErrorCause` (`@Enumerated(STRING)`, nullable, new enum: `TIMEOUT, AUTH, VALIDATION, OTHER`). Duration is **computed** from `completedAt - startedAt`, not a stored column. |

Both new columns must be nullable at the DB level — MySQL's strict mode rejects `ALTER TABLE ... ADD
COLUMN ... NOT NULL` with no default against an already-populated table. Application code fills both
fields for every new run; legacy rows read back `null` and are treated as "predates this feature",
not an error.

## Stamping points

- `WorkflowExecutionService.enqueue()` — sets `triggerType = MANUAL` when building the `WorkflowRun`.
- `WorkflowExecutionService.execute()`:
  - the node-failure branch (`!result.isSuccess()`, currently lines 235-242) calls
    `classifyError(composeError(result))` — this is where the vast majority of real failures land,
    since every executor already converts its own exceptions to a message here.
  - the outer whole-run catch (currently lines 244-247) checks `e instanceof IllegalArgumentException`
    first (→ `VALIDATION`, covers bad `graph_json`) before falling back to
    `classifyError(e.getMessage())` for anything else uncaught at that level.
  - the classified `ErrorCause` is persisted on the `WorkflowRun` when `status` is set to `FAILED`.

## Endpoints

All under `/api/dashboard`, JWT-secured, scoped via `currentUser.requireUserId(authentication)`.

| Endpoint | Query params | Powers |
|---|---|---|
| `GET /api/dashboard/overview` | `days` (default 30) | Bundles all of the below, for initial page load |
| `GET /api/dashboard/summary` | — | 4 KPI cards |
| `GET /api/dashboard/executions-over-time` | `days` (default 30) | Area chart |
| `GET /api/dashboard/executions-by-trigger` | `days` (default 7) | Donut chart |
| `GET /api/dashboard/failures-by-cause` | `days` (default 10) | Stacked bar |
| `GET /api/dashboard/median-run-duration` | `days` (default 7) | Line chart |

## DTOs (`dashboard/dto/`, Java records — matches existing request-DTO convention)

- `SummaryStatsDTO{executionsToday, executionsTodayDeltaPct, successRatePct, successRateDeltaPp, failedRuns, failedRunsDeltaPct, medianRunTimeSec, medianRunTimeDeltaPct}`
- `TimeSeriesPointDTO{date, count}` + `ExecutionsOverTimeDTO{points, deltaPct}`
- `TriggerBreakdownDTO{manualPct, schedulePct, webhookPct, deltaPp}` (schedule/webhook read 0 until that feature exists)
- `FailureCauseBucketDTO{date, cause, count}` (flat list; service aggregates per date × cause). `cause` is `ErrorCause{TIMEOUT, AUTH, VALIDATION, OTHER}`.
- `DurationTrendDTO{dayLabel, medianSec}` + `MedianRunDurationDTO{points, deltaPct}`
- `DashboardOverviewDTO{summary, executionsOverTime, executionsByTrigger, failuresByCause, medianRunDuration}`

## Query & aggregation approach

MySQL has no `PERCENTILE_CONT`/`date_trunc`, and the codebase's existing `@Query` usages are all
JPQL (no native SQL precedent). So: fetch bounded raw rows for the requested day range via JPQL
(`WHERE workflow.user.id = :userId AND startedAt >= :since`), then bucket-by-day and compute medians
in Java (sort durations, take the middle value(s)). This stays portable to H2 for tests and doesn't
introduce the first native/dialect-specific query in the repo.

## Deferred (YAGNI)

Caffeine caching and a `run_daily_stats` rollup table — not needed until well past a few hundred
thousand runs. Can be layered in later behind the same `DashboardService` interface without changing
the controller or DTO contracts.

## Definitions

- "Today" / day boundaries use the UTC calendar day (all timestamps in the domain are `Instant`; no
  user-timezone concept exists anywhere in the codebase today).
- `successRatePct` is computed over **completed** runs only (`SUCCESS` + `FAILED`) in the window —
  `PENDING`/`RUNNING` runs are excluded from the denominator since they haven't reached a terminal
  state.

## Edge cases

- New user / workflow with zero runs → every endpoint returns zero-valued or empty DTOs, never an
  error.
- Delta-vs-previous-period math: previous period had 0 executions → delta is `null`, not
  `NaN`/`Infinity`.
- Legacy runs with `triggerType`/`errorCause` still `null` (rows written before this feature ships,
  if any exist by then) are excluded from `executions-by-trigger` and `failures-by-cause`
  respectively — consistent with this codebase's existing convention of treating a null on an
  additive column as "predates this field," not "unknown bucket to display." They still count
  toward `failedRuns` in the KPI summary, since that's a plain status count independent of cause.

## Testing

Integration tests under the `test` profile (H2), seeding `Workflow`/`WorkflowRun` rows directly
through repositories in `@BeforeEach`, asserting JSON via `MockMvc` — same pattern as the existing
workflow/run tests.

## Explicitly out of scope for this spec

- Real scheduled/webhook triggering (§ scope decision 1)
- Changing `HttpNodeExecutor` to treat non-2xx responses as failures (§ scope decision 2)
- `Workflow.status`, `WorkflowActivityLog`, busiest-workflows, recent-executions, activity feed (§ scope decision 3 — see superseded spec if revived later)
- Frontend data-fetching layer / adapting DTOs to existing chart component prop shapes
- Caching and rollup tables (§ Deferred)
