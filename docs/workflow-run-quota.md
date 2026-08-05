# Workflow Run Quota (Lifetime Cap: 10 runs/user)

**Status:** Implemented, merged to `main` pending review
**Author:** Swayam Terode (with Claude Code)
**Date:** 2026-08-06

## What this is

Every user now gets a **lifetime cap of 10 workflow runs** (executions), counted across all
of their workflows combined. Once a user has enqueued 10 runs — ever — any further run
attempt is rejected with `402 Payment Required` until they subscribe.

This ships the counting + enforcement mechanism only. **Subscription/payment enforcement
itself is not built yet** — there's just one exemption today (`ADMIN` role), and the code is
structured so plugging in "has an active subscription" later is a one-line change (see
[Extending for subscriptions](#extending-for-subscriptions)).

## Why

We need a usage ceiling on the free tier before a paid plan exists, so we don't give away
unlimited compute. 10 lifetime runs is the free allowance; subscribing (future work) will
raise or remove it.

## Behavior

- **What counts:** every workflow *run* (execution), not every workflow *created*. Creating
  workflows is unlimited; running them is capped.
- **When it counts:** the moment a run is enqueued (`PENDING`), regardless of whether it later
  succeeds or fails. A user who runs a workflow that fails still spends one of their 10 — the
  cap gates *attempts*, not successes.
- **It's a lifetime, monotonic count** — it never decreases. Deleting a workflow (and its run
  history with it) does **not** refund any runs. This was a deliberate design choice — see
  [Why not just COUNT the rows?](#why-not-just-count-the-rows) below.
- **`ADMIN` users are exempt** — unlimited runs, no counting.
- **Everyone else** gets exactly 10, then a `402` on the 11th attempt.

## API changes

### `POST /api/workflows/{id}/run` — now can return 402

No change to the happy path (still `202 Accepted` with the run summary). New behavior once
the caller is at their cap:

```
HTTP/1.1 402 Payment Required
Content-Type: application/json

{
  "timestamp": "2026-08-06T10:15:00Z",
  "status": 402,
  "error": "Payment Required",
  "message": "You've reached your limit of 10 workflow runs. Subscribe to continue.",
  "path": "/api/workflows/42/run"
}
```

This is the same `ErrorResponse` shape every other error in the API already uses — no new
client-side parsing needed, just handle status `402` alongside the `404`/`409` you already
handle for this endpoint.

### `GET /api/workflows/runs/usage` — new

Lets the frontend show usage ("7 of 10 runs used") *before* the user hits the wall, instead of
only finding out via a failed `POST /run`.

Request: none (uses the caller's auth token, same as every other endpoint).

Response, regular user:
```json
{ "used": 7, "limit": 10, "remaining": 3, "unlimited": false }
```

Response, ADMIN (or, later, an active subscriber):
```json
{ "used": 0, "limit": null, "remaining": null, "unlimited": true }
```

`limit`/`remaining` are `null` when `unlimited` is `true` — don't render "0 / null", check
`unlimited` first.

## How it works internally

### Where the counter lives

`users.workflow_run_count` (`Integer`, nullable) — a new column on the existing `User` entity.
It's a plain denormalized counter, not a `COUNT(*)` over `workflow_runs`. This matters:

#### Why not just COUNT the rows?

`WorkflowExecutionService.deleteWithHistory()` already lets you delete a workflow (and every
`workflow_run` row that belongs to it) once none of its runs are still in flight. If the cap
were a live `COUNT(*)` over `workflow_runs`, the loophole is obvious: hit 10, delete the
workflow, count drops back to 0, run 10 more, repeat forever. A denormalized counter that only
ever increments closes that hole by construction — it's a fact about the *user*, not a
derived fact about currently-existing rows, so deleting workflows can't touch it.

We added a regression test (`deletingAWorkflowDoesNotResetTheUsersLifetimeCount`) specifically
to pin this down.

### How the increment is race-safe

The increment is a single conditional SQL `UPDATE`, not a read-then-write:

```sql
update users set workflow_run_count = coalesce(workflow_run_count, 0) + 1
where id = :id and coalesce(workflow_run_count, 0) < :limit
```

(`UserRepository.incrementRunCountIfUnderLimit`)

This returns `1` if it incremented, `0` if the user was already at the cap. No explicit
`@Lock` annotation is needed — the database row-locks the matched `users` row for the
statement's duration, so two concurrent run requests from the *same* user serialize correctly
(the second one re-evaluates the `WHERE` against the first one's committed result). Different
users never contend, since they're different rows.

`coalesce(..., 0)` exists because this project has no Flyway/migration tooling
(`ddl-auto=update`), so the column was added directly to an already-populated `users` table
with no backfill — every pre-existing user reads back `NULL` until they run something. Without
the `coalesce`, `NULL + 1` and `NULL < 10` are both SQL `NULL` (unknown), which would silently
block every existing user from ever incrementing. New users get a real `0` from
`User`'s `@PrePersist` hook, so this only matters for accounts created before this feature
shipped.

### Where enforcement happens

`WorkflowExecutionService.enqueue(Long workflowId)` is the single place a `WorkflowRun` row is
ever created (it's called from both the `POST /run` endpoint and nowhere else). The check was
added right there, before the run is persisted, and the whole method is now `@Transactional`
so "counted" and "actually created a PENDING run" always commit or roll back together:

```java
@Transactional
public WorkflowRun enqueue(Long workflowId) {
  Workflow workflow = workflowRepository.findById(workflowId).orElseThrow(...);
  quotaService.enforceQuota(workflow.getUser().getId());   // throws 402 if capped
  return workflowRunRepository.save(
      WorkflowRun.builder().workflow(workflow).status(WorkflowRunStatus.PENDING).build());
}
```

If `enforceQuota` throws, the method returns before `save(...)` — a rejected attempt never
gets a row and never double-counts.

### The quota service itself

New class: `com.flowmatic.auth.workflow.execution.WorkflowRunQuotaService`. Two public methods,
one private extension point:

```java
public void enforceQuota(Long userId)          // increments or throws 402
public WorkflowRunUsageDTO usage(Long userId)  // read-only, for the GET endpoint

private boolean isExempt(User user) {          // <-- the one place to extend later
  return user.getRole() == Role.ADMIN;
}
```

The cap itself is configurable via `app.workflow.lifetime-run-limit` (defaults to `10`), set in
`application.properties` / `application-example.properties`. Changing it is a config change,
not a code change.

## Extending for subscriptions

The design intentionally leaves exactly one seam for the future subscription feature:
`WorkflowRunQuotaService.isExempt(User)`. When subscriptions exist, this becomes:

```java
private boolean isExempt(User user) {
  return user.getRole() == Role.ADMIN || subscriptionService.hasActivePlan(user);
}
```

Nothing else in this feature — the controller, `enqueue()`, the usage DTO, the error
contract — needs to change. If subscribed users should get a *higher* cap rather than
*unlimited*, `lifetimeRunLimit` would become per-user instead of a single `@Value`, but that's
a deliberately deferred decision, not something this change tried to guess at.

## Files touched

| File | What changed |
|---|---|
| `entity/User.java` | New nullable `workflowRunCount` column, defaulted to `0` on create |
| `repository/UserRepository.java` | New `incrementRunCountIfUnderLimit(id, limit)` atomic update |
| `workflow/execution/WorkflowRunQuotaService.java` | **New.** `enforceQuota`, `usage`, `isExempt` |
| `workflow/execution/WorkflowRunUsageDTO.java` | **New.** `record(used, limit, remaining, unlimited)` |
| `workflow/execution/WorkflowExecutionService.java` | `enqueue()` now checks quota and is `@Transactional` |
| `workflow/execution/WorkflowRunController.java` | New `GET /runs/usage` endpoint |
| `application.properties`, `application-example.properties` | New `app.workflow.lifetime-run-limit=10` |

## Test coverage

Built test-first (TDD); 9 new tests, full suite (89 tests) green with no regressions.

`WorkflowRunQuotaIntegrationTest` (service-level, direct calls):
- 10th run succeeds, 11th is rejected with 402, and no 11th row is ever persisted
- Cap is per-user across multiple workflows (5 + 5 = 10, not reset by switching workflows)
- `ADMIN` can run past 10 with no rejection
- Deleting a workflow does **not** reset the user's count (the loophole this design closes)
- `usage()` reports correct used/remaining at 0, 3, and 10 runs; unlimited for ADMIN

`WorkflowRunUsageEndpointIntegrationTest` (MockMvc, HTTP layer):
- Fresh user sees `used:0, limit:10, remaining:10`
- Usage reflects runs as they're enqueued
- `POST /run` returns 402 with the subscribe-to-continue message once capped
- Confirms `GET /runs/usage` doesn't get swallowed by the existing `GET /runs/{runId}` route
  (this was a real risk worth pinning down — Spring MVC does resolve the literal path
  correctly, but only a test proves it stays that way)

Run locally: `./mvnw test -Dtest=WorkflowRunQuotaIntegrationTest,WorkflowRunUsageEndpointIntegrationTest`

## What's explicitly NOT in this change

- No subscription/payment/billing logic of any kind — just the one exemption seam.
- No per-plan/tiered limits — the cap is a single global constant.
- No "reset monthly" or rolling-window behavior — it's a lifetime, one-time allowance.
- No UI — the frontend team can build against `GET /runs/usage` and the `402` contract above.
