# Dashboard Executions-by-Status Endpoint — Design Spec

Date: 2026-08-08

## Goal

Add `GET /api/dashboard/executions-by-status`, backing a pie/donut chart on the dashboard. The
frontend mock this replaces showed a trigger-type breakdown (webhook/schedule/manual) — confirmed
with user that this isn't real: `WorkflowRun` has no trigger field on `main`, and no scheduler or
webhook-trigger path exists anywhere in this codebase, so a real trigger breakdown would be 100%
`MANUAL` (one populated slice, not the 3–5 the chart needs). This spec repurposes the same chart
slot for a dimension that's genuinely backed by data today: run status.

Same package (`com.flowmatic.auth.workflow.dashboard`), same conventions as the two existing
endpoints in it: JWT auth via `Authentication`, manual ownership checks via
`currentUser.requireUserId(authentication)`, no `@PreAuthorize`, no `ApiResponse<T>` wrapper,
`ResponseEntity<T>` returned directly.

## Endpoint

`GET /api/dashboard/executions-by-status` — no query params, fixed to the **last 30 days**
(matches `executions-over-time`'s default window, so the dashboard's widgets agree on what "recent
activity" means).

## Response

A flat list of all 4 `WorkflowRunStatus` values — `PENDING`, `RUNNING`, `SUCCESS`, `FAILED` — always
all 4, even at `count: 0` (consistent with the zero-fill philosophy of `executions-over-time`: a
predictable, complete list the frontend never has to guess is exhaustive). Raw counts, not
percentages — matches the "backend returns numbers, frontend formats" convention established by
both existing endpoints in this package; the frontend computes each slice's share and color from
the counts.

```json
[
  { "status": "SUCCESS", "count": 142 },
  { "status": "FAILED", "count": 9 },
  { "status": "RUNNING", "count": 1 },
  { "status": "PENDING", "count": 0 }
]
```

Order is fixed (`PENDING, RUNNING, SUCCESS, FAILED` — declaration order of the enum), not
count-sorted, so the frontend's color mapping stays stable across requests.

## DTO

`com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO` — a record:

```java
record StatusBreakdownDTO(String status, long count) {}
```

`status` is `WorkflowRunStatus.name()` (e.g. `"SUCCESS"`), a plain string — matches the existing
DTOs' preference for primitive/string fields over embedding the enum type directly.

## Implementation

No new repository method needed — reuses the existing
`WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(userId, since)` (added for
`executions-over-time`), with `since` = 30 days before `now`'s UTC calendar day start. Groups the
fetched rows by `status` in Java, then emits all 4 `WorkflowRunStatus` values in declaration order,
substituting `0` for any status with no matching rows.

## Edge cases

- New user / zero runs in the last 30 days → all 4 statuses at `count: 0`, never an error.
- A run exactly 30 days ago is included; one day further back is excluded — same boundary
  convention as `executions-over-time`'s single-sided `>=` query.

## Testing

Same pattern as the other two endpoints: `@SpringBootTest` + H2, seeding `User`/`Workflow`/
`WorkflowRun` rows via repositories, `@WithMockUser` + a seeded matching `User` row, asserting the
JSON array via `MockMvc`. Cases: zero-data new user (all 4 at 0); a realistic mix of statuses
counted correctly; scoping (another user's runs never appear); a run just outside the 30-day
window excluded.

## Explicitly out of scope

- Real trigger-type tracking (webhook/schedule triggering) — would need actual scheduler/webhook
  infrastructure that doesn't exist; not requested here.
- `NodeRunStatus` (node-level, not run-level) breakdown — considered as an alternative, not chosen.
- Any query-param-driven window customization — fixed 30-day window, matching the other endpoints'
  defaults.
