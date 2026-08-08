# Dashboard Executions-Over-Time Endpoint — Design Spec

Date: 2026-08-08

Note: earlier specs/plans for a larger dashboard page (4 KPI cards + 4 charts) were written and
partly implemented, then discarded from this branch. This spec covers only the one chart the
current frontend mock (`ExecutionRow[]`) needs — no KPI cards, trigger/failure breakdowns, or
median-duration chart. `TriggerType`/`ErrorCause` on `WorkflowRun` and their classification in
`WorkflowExecutionService` already exist from that earlier work and are unaffected by this spec.

## Goal

Add one endpoint that returns per-day execution counts for the caller's own workflows, over a
caller-chosen window of 7, 30, or 60 days, in the exact shape the frontend already expects:

```ts
interface ExecutionRow { date: string; executions: number }
```

New package `com.flowmatic.auth.workflow.dashboard`, containing everything for this feature
(controller, service, DTO) — nothing added to `workflow.execution` or other existing packages
besides reading their public repositories/entities. Following existing project conventions: JWT
auth via `Authentication`, manual ownership checks via `currentUser.requireUserId(authentication)`,
no `@PreAuthorize`, no `ApiResponse<T>` wrapper, `ResponseEntity<T>` returned directly. Frontend
wiring is out of scope — backend API only.

## Scope decisions (confirmed with user)

1. **One endpoint, one `days` param** — `GET /api/dashboard/executions-over-time?days={7|30|60}`
   (default 30), returning a flat list for that window. Not three bundled arrays in one response —
   the frontend re-fetches when the range toggle changes.
2. **"Execution" = every `WorkflowRun`, any status** — counts `PENDING`/`RUNNING`/`SUCCESS`/`FAILED`
   runs by the UTC calendar day of `startedAt`. This is a raw activity count, not a completed-only
   count. A `PENDING` run that hasn't started yet (`startedAt == null`, still queued behind the
   FIFO drainer) has no day to bucket into and is excluded — it will be counted once it starts.
3. **Zero-fill every day in the window** — the response always has exactly `days` entries, oldest
   first, one per calendar day, with `executions: 0` for days with no runs. No gaps for the frontend
   to handle.

## Endpoint

| Endpoint | Query params | Response |
|---|---|---|
| `GET /api/dashboard/executions-over-time` | `days` — must be `7`, `30`, or `60` | `200 OK`: `ExecutionRowDTO[]`, oldest day first |

- JWT-secured (covered by the existing default `authenticated()` matcher — no `SecurityConfig`
  change needed), scoped via `currentUser.requireUserId(authentication)`.
- `days` outside `{7, 30, 60}` → `400 Bad Request` via `ResponseStatusException`, matching the
  codebase's existing error convention (e.g. `WorkflowRunController`'s 404s).
- "Today" / day boundaries use the UTC calendar day, consistent with every other date-handling in
  this codebase (all timestamps are `Instant`; no user-timezone concept exists anywhere yet). The
  window is `[today - (days - 1), today]` UTC, inclusive of today.

## DTO

`com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO` — a Java record:

```java
record ExecutionRowDTO(String date, long executions) {}
```

`date` is an ISO `yyyy-MM-dd` string (`LocalDate.toString()`), matching the frontend type exactly.

## Query & aggregation approach

Reuses the same pattern as the (unimplemented) prior dashboard spec: MySQL has no
`date_trunc`/`GROUP BY DATE()` precedent in this codebase's existing `@Query` usages (all JPQL), so
fetch bounded raw rows for the window via a new repository method, then bucket-by-day in Java. 60
days of one user's runs is a small, bounded fetch — no need for a native/dialect-specific query.

New method on `WorkflowRunRepository`:

```java
List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long userId, Instant since);
```

`DashboardService.executionsOverTime(userId, days)`:
1. Compute `since` = start of UTC day `(today - (days - 1))`.
2. Fetch runs via the repository method above.
3. Group by UTC calendar day of `startedAt`, count per day.
4. Walk `since` → `today` day by day, emitting `ExecutionRowDTO(day, countOrZero)` for each.

## Edge cases

- New user / user with zero workflows or runs → every day in the window returns `executions: 0`,
  never an error.
- Runs still `PENDING` with `startedAt == null` → excluded from the fetch (the query filters on
  `startedAt >= since`, and `null >= x` is never true in SQL), consistent with counting only runs
  that have actually started.
- `days` values other than 7/30/60 (e.g. `days=14`) → `400`, not silently clamped — the frontend
  only ever sends one of the three supported values.

## Testing

`@SpringBootTest`, `@ActiveProfiles("test")` (H2), `@MockitoBean JavaMailSender mailSender` (needed
by any `@SpringBootTest` in this codebase per existing convention), seeding `User`/`Workflow`/
`WorkflowRun` rows directly through repositories in each test with explicit `startedAt` values,
asserting the JSON array via `MockMvc` — same pattern as `WorkflowTriggerAndErrorCauseIntegrationTest`.
Cases:
- Zero-fill: no runs at all → response has `days` entries, all zero.
- Per-day bucketing: multiple runs on the same UTC day count together; runs just before/after a UTC
  day boundary land in the correct bucket.
- Scoping: another user's runs never appear in the caller's counts.
- Validation: `days=14` → `400`.
- Window edges: a run exactly `days` days ago is included; one day further back is excluded.

## Explicitly out of scope

- KPI summary cards, executions-by-trigger donut, failures-by-cause breakdown, median-run-duration
  chart — everything from the earlier, larger dashboard spec beyond this one chart. If any of these
  get built later, they're separate specs/endpoints added to this same `workflow.dashboard` package.
- Frontend wiring (swapping the mock `chartData` array for a real fetch).
- Per-workflow breakdown — this is an aggregate across all of the caller's workflows, matching the
  `ExecutionRow` shape (no workflow identifier in it).
