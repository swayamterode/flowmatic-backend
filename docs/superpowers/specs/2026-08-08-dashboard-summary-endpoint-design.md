# Dashboard Summary Stats Endpoint — Design Spec

Date: 2026-08-08

## Goal

Add `GET /api/dashboard/summary`, backing the 4 KPI cards (Executions today, Success rate, Failed
runs, Median run time) shown in the frontend's `Stat[]` mock. Same package
(`com.flowmatic.auth.workflow.dashboard`), same conventions as the existing executions-over-time
endpoint: JWT auth via `Authentication`, manual ownership checks via
`currentUser.requireUserId(authentication)`, no `@PreAuthorize`, no `ApiResponse<T>` wrapper,
`ResponseEntity<T>` returned directly. Frontend wiring and value formatting (commas, `%`, `s`
suffix, delta arrows/colors) are out of scope — the backend returns raw numbers only. The
`lowerIsBetter` flag from the mock is a static per-metric constant, not data — it stays
frontend-only, not part of this API.

## Endpoint

`GET /api/dashboard/summary` — no query params (all four cards use fixed windows, confirmed with
user). Returns one `SummaryStatsDTO`, not a list.

## Windows (UTC calendar days, consistent with the existing executions-over-time endpoint)

- **Day pair** (Executions today, Failed runs): current = today `[todayStart, tomorrowStart)`,
  comparison = yesterday `[yesterdayStart, todayStart)`.
- **Week pair** (Success rate, Median run time): current = last 7 days
  `[todayStart.minusDays(6), tomorrowStart)`, comparison = the preceding 7 days
  `[todayStart.minusDays(13), todayStart.minusDays(6))`.

Confirmed with user: day-pair cards use single-day windows (matches "vs yesterday"); week-pair
cards use rolling 7-day windows (matches "vs last week") since a rate/median is noisy over a single
day.

## Repository

New bounded-range method on `WorkflowRunRepository`:

```java
List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
    Long userId, Instant from, Instant to);
```

The existing `findByWorkflow_User_IdAndStartedAtGreaterThanEqual` (added for the
executions-over-time endpoint) has no upper bound — "yesterday" and "the preceding 7 days" are both
bounded on both ends, so a new method is needed. This one fetch, called four times (once per
window), is sufficient — no new indices needed at this data scale, consistent with the existing
endpoint's "fetch bounded rows, compute in Java" approach.

## Computation rules

- `executionsToday` (`long`) — count of runs (any status) with `startedAt` in the today window.
  Always defined (0 if none).
- `failedRuns` (`long`) — count of `FAILED`-status runs with `startedAt` in the today window.
  Always defined.
- `successRatePct` (`Double`, nullable) — `100 * SUCCESS / (SUCCESS + FAILED)` over runs with
  `startedAt` in the last-7-days window. `null` if that window has zero completed
  (`SUCCESS`+`FAILED`) runs — undefined, not zero. `PENDING`/`RUNNING` runs are excluded from both
  numerator and denominator (not yet in a terminal state).
- `medianRunTimeSeconds` (`Double`, nullable) — median of `completedAt - startedAt` in seconds,
  over completed runs (`SUCCESS`+`FAILED` — a failed run's duration up to the point of failure is
  still a real measurement) with `startedAt` in the last-7-days window. `null` if that window has
  zero completed runs. Even count of durations: average of the two middle values.
- Every delta is **null**, never `NaN`/`Infinity`, when its baseline can't support the computation:
  - `executionsTodayDeltaPct` (`Double`) — `null` if yesterday's `executionsToday` was 0; otherwise
    `100 * (today - yesterday) / yesterday`.
  - `failedRunsDeltaPct` (`Double`) — same rule, over `failedRuns`.
  - `successRateDeltaPp` (`Double`) — percentage-**point** difference (matches the mock's
    `deltaSuffix: "pp"`): `currentRate - previousRate`. `null` if either window's `successRatePct`
    is `null`.
  - `medianRunTimeDeltaPct` (`Double`) — `100 * (current - previous) / previous`. `null` if either
    window's median is `null`, or if the previous median is `0` (guards a division by zero, even
    though a genuine 0-second run is unlikely in practice).

## DTO

`com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO` — a Java record:

```java
record SummaryStatsDTO(
    long executionsToday,
    Double executionsTodayDeltaPct,
    Double successRatePct,
    Double successRateDeltaPp,
    long failedRuns,
    Double failedRunsDeltaPct,
    Double medianRunTimeSeconds,
    Double medianRunTimeDeltaPct) {}
```

`Double` (boxed) is used, not `double`, specifically so `null` serializes as JSON `null` rather than
being unrepresentable — matches the "undefined, not zero" semantics above.

## Edge cases

- New user / user with zero runs anywhere → `executionsToday = 0`, `failedRuns = 0`, all four
  `Double` fields `null`.
- A run exactly at a window's lower boundary is included; one instant before is excluded (`>=`
  lower, `<` upper) — same boundary convention as the existing endpoint's single-sided query.
- Previous-period rate/median undefined (zero completed runs) correctly nulls the delta even when
  the current period has data.

## Testing

Same pattern as the existing endpoint: `@SpringBootTest` + H2, seeding `User`/`Workflow`/
`WorkflowRun` rows via repositories with explicit `startedAt`/`completedAt`, asserting the JSON body
via `MockMvc` with `@WithMockUser` + a seeded matching `User` row. Cases: all-zero/all-null for a
new user; non-zero counts landing in the correct window; a run just outside a window boundary
excluded; delta math including the null-baseline case for each of the four deltas; success rate and
median excluding `PENDING`/`RUNNING` runs from their computation.

## Explicitly out of scope

- Value formatting (commas, `%`, `s` suffix) and the `lowerIsBetter` flag — frontend-only.
- Executions-by-trigger donut, failures-by-cause breakdown — not requested, no data model exists
  for either beyond what was already scoped out of the executions-over-time spec.
- Any query-param-driven window customization — the four cards use fixed windows per the mock.
