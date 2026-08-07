# Stripe Subscription Billing

**Status:** Implemented on branch `worktree-stripe-subscription-billing`, not yet merged to `main`
**Author:** Swayam Terode (with Claude Code)
**Date:** 2026-08-07

## What this is

Three self-serve, Stripe-backed subscription plans (Essentials/Pro/Enterprise) that raise or
remove the free-tier lifetime workflow-run cap described in
[`workflow-run-quota.md`](./workflow-run-quota.md). Checkout is Stripe's own hosted page (no card
data ever touches this backend); a Stripe webhook is the sole source of truth for who's on what
plan — the checkout redirect back to the frontend is UX only.

| Plan | Run limit | Price |
|---|---|---|
| Essentials | 100 lifetime runs | $9/mo |
| Pro | 1,000 lifetime runs | $29/mo |
| Enterprise | Unlimited | $99/mo |

## Why

The quota feature shipped with exactly one exemption (`ADMIN`) and an explicit seam
(`WorkflowRunQuotaService.isExempt`) for "later, plug in a real subscription check here." This is
that seam getting filled in — a real payment flow that assigns a plan on success and keeps it in
sync with Stripe for the life of the subscription (renewals, failed payments, cancellations).

## Architecture

New package `com.flowmatic.auth.billing`, parallel to `workflow`:

```
billing/
  entity/
    Subscription.java        — 1:1 with User; stripeCustomerId, stripeSubscriptionId, plan,
                                status, currentPeriodEnd
    SubscriptionPlan.java    — ESSENTIALS, PRO, ENTERPRISE
    SubscriptionStatus.java  — ACTIVE, PAST_DUE, CANCELED
  repository/
    SubscriptionRepository.java  — findByUserId, findByStripeSubscriptionId
  StripeConfig.java          — the ONE place that constructs a StripeClient bean
  PlanLimits.java            — SubscriptionPlan -> configured numeric run limit
  StripePlanProperties.java  — SubscriptionPlan <-> Stripe Price ID, both directions
  SubscriptionService.java   — activePlan, hasActiveSubscription, stripeCustomerId,
                                upsertFromCheckout, updateFromStripeSubscription, markCanceled
  StripeCheckoutService.java — builds a Checkout Session for a plan
  StripeWebhookService.java  — verifies signature, dispatches by event type
  web/
    BillingController.java       — POST /checkout-session, POST /webhook
    CheckoutSessionRequest.java  — { "plan": "ESSENTIALS" | "PRO" | "ENTERPRISE" }
```

**Every Stripe API call goes through one injected `StripeClient` bean** (`StripeConfig`) — never
a static `Stripe.apiKey = ...` global. This is what makes `StripeCheckoutService` and
`StripeWebhookService` unit-testable with `mock(StripeClient.class, RETURNS_DEEP_STUBS)`, the same
pattern this codebase already used for `ChatClient` in the workflow AI feature.

**A deliberate naming split avoids a class collision.** Stripe's own model class is also called
`Subscription` (`com.stripe.model.Subscription`), colliding with this codebase's JPA entity of the
same name. `StripeWebhookService` is the *only* class that ever imports Stripe's model class — it
extracts primitives/enums/`Instant` from it before calling into `SubscriptionService`, which
imports the JPA entity in a separate file. Neither file needs both types.

## Data flow: starting a subscription

1. Frontend calls `POST /api/billing/checkout-session` with `{ "plan": "ESSENTIALS" }` (JWT
   required).
2. `StripeCheckoutService.createCheckoutSession`:
   - `409` if the user already has an `ACTIVE` subscription (any plan) — no
     upgrade/downgrade flow exists yet, so a second checkout while subscribed is rejected rather
     than risking a double charge.
   - Reuses the user's existing `stripeCustomerId` if one is on file (e.g. from a prior canceled
     subscription); otherwise lets Stripe create a new customer.
   - Builds a **subscription-mode** Checkout Session: the plan's Price ID (looked up via
     `StripePlanProperties`), `client_reference_id = userId` (how the webhook maps back to a
     local user — more reliable than matching on email), success/cancel URLs from config.
   - Returns `{ "checkoutUrl": session.getUrl() }`.
3. Frontend redirects the full browser window there. User pays on Stripe's hosted page.
4. Stripe redirects back to the success/cancel URL. **That redirect is UX only — it does not grant
   access.** The webhook is what actually activates the plan, and it can land before or after the
   redirect.

## Data flow: the webhook (source of truth)

`POST /api/billing/webhook` is public (`SecurityConfig.PUBLIC_ENDPOINTS`) — Stripe is the caller,
authenticated by its request signature, not a JWT. The controller reads the **raw body as a
`byte[]`, decoded as UTF-8 explicitly** (not left to Spring's default charset, which is
ISO-8859-1 in this Spring version — Stripe's HMAC is computed over UTF-8 bytes).

`StripeWebhookService.handleWebhook`:

```java
Event event = stripeClient.constructEvent(payload, sigHeader, webhookSecret); // throws on bad sig
switch (event.getType()) {
  case "checkout.session.completed" -> handleCheckoutCompleted(event);
  case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
  case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
  default -> log.info(...); // ack with 200, ignore — Stripe sends far more event types than we care about
}
```

| Event | Action |
|---|---|
| `checkout.session.completed` | Guard: ignore (log + return, no exception) if `mode != "subscription"`, or `clientReferenceId`/`subscription` is null/unparseable — this event fires for **every** Checkout Session on the whole account, not just ones this app created (Dashboard sessions, Payment Links, the Stripe CLI's own generic trigger command). Otherwise: retrieve the full `Subscription` from Stripe, resolve the plan from its line item's Price ID, and `SubscriptionService.upsertFromCheckout(...)`. |
| `customer.subscription.updated` | Look up by `stripeSubscriptionId`; update status/plan/period-end. Covers renewals and `active -> past_due`. Unrecognized subscription ID or Price ID → logged, no-op (not an error — a misconfiguration shouldn't make Stripe retry forever). |
| `customer.subscription.deleted` | Look up by `stripeSubscriptionId`; set `status = CANCELED`. Unrecognized ID → logged, no-op. |

**Cancellation-revokes-access falls out of the quota mechanism for free.** `workflowRunCount` is a
monotonic lifetime counter (see the quota doc). A canceled Essentials subscriber who used 80 of
their 100 runs reverts to the free tier's 10 the instant `status` flips to `CANCELED` — 80 > 10, so
they're blocked until they resubscribe. No cancellation-specific logic needed.

### A real bug this shipped with, and the fix

`event.getDataObjectDeserializer().getObject()` returns `Optional.empty()` — not on malformed
JSON, but whenever the event's `api_version` doesn't exactly match the SDK's compiled-in version.
Stripe stamps every webhook with the **account's** API version, not the SDK's, so in practice this
was empty on essentially every real event, and the original code did
`.getObject().orElseThrow(...)` — a 500 on every webhook delivery, forever, which is as broken as
this feature could possibly be while still passing its own test suite (the test fixtures happened
to hardcode the SDK's exact version string). Fixed by falling back to `deserializeUnsafe()`
whenever `getObject()` is empty — Stripe's own documented pattern for exactly this case. Caught by
a final whole-branch review, not by any per-task review, because no single task's diff showed the
gap between "the SDK's version" and "a real account's version."

## The quota integration

`WorkflowRunQuotaService` (existing) grew two small resolution steps that consult
`SubscriptionService`:

```java
private boolean isExempt(User user) {
  return user.getRole() == Role.ADMIN
      || subscriptionService.activePlan(user.getId())
          .filter(plan -> plan == SubscriptionPlan.ENTERPRISE)
          .isPresent();
}

private int effectiveLimit(User user) {
  return subscriptionService.activePlan(user.getId())
      .map(planLimits::forPlan)       // ESSENTIALS -> 100, PRO -> 1000
      .orElse(lifetimeRunLimit);      // free tier default (10), unchanged
}
```

`UserRepository.incrementRunCountIfUnderLimit(userId, limit)` needed **no changes** — it already
took `limit` as a parameter, so per-plan limits fall out of changing which value gets passed in.
`GET /api/workflows/runs/usage` gained a `plan` field (the active plan's name, or `null`).

*Known, accepted tradeoff:* `isExempt`/`effectiveLimit` each independently call `activePlan` — up
to 2-3 DB round-trips (a cheap indexed point-query by `user_id`) per quota check, instead of one.
Flagged in final review; left as-is deliberately — workflow runs are already serialized through a
single-threaded scheduler, so this was never a real hot path, and the two methods stay simple and
independently readable.

## Error handling

| Situation | Response |
|---|---|
| Invalid/missing webhook signature | `400`, no DB write |
| Webhook references an unrecognized `stripeSubscriptionId` or Price ID | Logged, no-op, still `200` (don't make Stripe retry a permanent misconfiguration) |
| `checkout-session` while already subscribed | `409` |
| `checkout-session` with an invalid `plan` enum value | `400` (via a `GlobalExceptionHandler` fix for `HttpMessageNotReadableException`, which — a real, pre-existing, app-wide gap this feature exposed — doesn't implement Spring's `ErrorResponse` in this Spring version and was otherwise falling through to a raw `500`) |
| Stripe API errors during checkout-session creation | `502`, generic message, no leaked SDK stack trace (`GlobalExceptionHandler` catches `StripeException`) |

## Files touched

| File | What changed |
|---|---|
| `pom.xml` | `com.stripe:stripe-java:33.2.0` |
| `billing/**` | New package, ~10 new files (see Architecture above) |
| `workflow/execution/WorkflowRunQuotaService.java` | `isExempt`/limit resolution now plan-aware |
| `workflow/execution/WorkflowRunUsageDTO.java` | Gained a `plan` field |
| `exception/GlobalExceptionHandler.java` | `StripeException` -> 502; `HttpMessageNotReadableException` -> 400 |
| `security/SecurityConfig.java` | `/api/billing/webhook` added to `PUBLIC_ENDPOINTS` |
| `application.properties` | `app.stripe.*`, `app.workflow.plan-limits.*`, `app.frontend.*` |

## Test coverage

Built test-first across 7 sequential tasks, each independently reviewed, plus a final
whole-branch review (which caught the `api_version` bug above). 130 tests, full suite green.

- `SubscriptionServiceTest` — plan resolution only counts `ACTIVE`; upsert-vs-update branching;
  no-op on unrecognized IDs.
- `PlanLimitsTest` / `StripePlanPropertiesTest` — plan <-> limit / plan <-> Price ID mapping.
- `StripeCheckoutServiceTest` — 409 already-subscribed, customer-ID reuse, correct session params
  (all via a deep-stubbed `StripeClient`, no real network).
- `StripeWebhookServiceTest` — realistic `Event`/`Subscription` fixtures built via
  `com.stripe.net.ApiResource.GSON` (Stripe's own recommended pattern, not hand-rolled JSON
  parsing); all 3 event types; unrecognized Price ID; the `api_version` mismatch regression case
  specifically.
- `WorkflowRunQuotaIntegrationTest` / `WorkflowRunUsageEndpointIntegrationTest` (extended) —
  Essentials/Enterprise limits via real H2-backed runs; a canceled subscriber immediately
  re-blocked.
- `BillingControllerIntegrationTest` — full MockMvc + Spring Security + `GlobalExceptionHandler`
  stack; only the two Stripe-facing services are mocked.

No test hits the real Stripe network. Manual/local verification: `stripe listen --forward-to
localhost:8081/api/billing/webhook` + a real test-mode Checkout with card `4242 4242 4242 4242`.

## What's explicitly NOT in this change

- No plan upgrade/downgrade — switching tiers means canceling and starting a fresh checkout.
- No Stripe customer billing portal (self-serve cancel/update-card) — the Stripe Dashboard is the
  only cancellation path today.
- No proration, trials, coupons, or metered billing.
- No frontend implementation — see `docs/frontend/credits-and-plans.md`.

---

## Interview questions

A mix of "explain this system" and general Stripe/webhook fundamentals it exercises.

### Design and architecture

**Q: Why does `StripeConfig` build a `StripeClient` bean instead of setting a static
`Stripe.apiKey`?**
A: Testability, mainly. A static global can't be swapped per-test, so every Stripe-facing class
would need either real network access or a static-mocking library in tests. An injected
`StripeClient` can be `mock(StripeClient.class, RETURNS_DEEP_STUBS)`'d exactly like any other
collaborator — `StripeCheckoutServiceTest`/`StripeWebhookServiceTest` never touch the network. It
also avoids global mutable state in a Spring app where multiple beans might otherwise assume
they're the only thing setting that key.

**Q: Why does `Subscription` live as its own entity 1:1 with `User`, instead of adding
`stripeCustomerId`/`plan`/`status` columns directly onto `User`?**
A: Matches this codebase's existing convention for related-but-separate concerns
(`WorkflowRun`, `UserIntegration` are both separate entities keyed to `User` rather than fields on
it). `User`'s `workflowRunCount` column already nudged in the field-on-User direction once for the
quota feature; piling billing state on top would make `User` a dumping ground for every unrelated
concern that happens to be per-user.

**Q: Why extract data from Stripe's model objects inside `StripeWebhookService` instead of
passing the Stripe SDK objects into `SubscriptionService` directly?**
A: Two reasons. First, a real naming collision — Stripe's own `Subscription` model class and this
app's JPA `Subscription` entity share a name, so importing both in one file forces ugly
fully-qualified references everywhere. Second, and more important even without the collision:
`SubscriptionService` shouldn't need to know anything about the Stripe SDK's object shapes at
all — it takes plain values (`String`, `Instant`, our own enums) and is trivially unit-testable
with plain Mockito, with zero coupling to a third-party library's API surface.

**Q: Walk me through why `current_period_end` is read from `subscription.getItems().getData().get(0).getCurrentPeriodEnd()` instead of `subscription.getCurrentPeriodEnd()`.**
A: Stripe removed `current_period_end` from the top level of the `Subscription` object in a
recent API version — it now only exists per subscription-item (relevant for the "flexible
billing," multiple-prices-per-subscription case). An earlier design draft assumed the old,
top-level field; this was caught and corrected before shipping by checking the actual API
reference for the pinned SDK version rather than trusting memorized API shape.

### Webhooks specifically

**Q: Why is the webhook endpoint in the security config's public-endpoints list? Isn't that a
security hole?**
A: It's public to Spring Security's JWT filter, but not unauthenticated — Stripe signs every
webhook request with an HMAC over the raw body using a shared secret (`STRIPE_WEBHOOK_SECRET`),
sent in the `Stripe-Signature` header. `stripeClient.constructEvent(payload, sigHeader, secret)`
verifies that signature and throws `SignatureVerificationException` (mapped to `400`) if it
doesn't match. A JWT wouldn't even make sense here — Stripe's servers, not a logged-in user, are
the caller.

**Q: Why does the webhook read the body as `byte[]` and decode it as UTF-8 explicitly, instead of
just taking a `String` parameter?**
A: Signature verification needs the *exact bytes* Stripe signed. Spring's `@RequestBody String`
decodes using `StringHttpMessageConverter`'s default charset, which is ISO-8859-1 in this Spring
version — not UTF-8. It happens to work today because Stripe always sends
`charset=utf-8`, but that's relying on a header Stripe controls rather than something this code
enforces. Reading raw bytes and decoding explicitly removes the dependency entirely.

**Q: Why is `checkout.session.completed` handled so defensively (mode/clientReferenceId/subscription-null checks), when this app is the only thing that ever creates its own Checkout Sessions?**
A: Because the webhook doesn't only fire for sessions *this app* created — Stripe sends
`checkout.session.completed` for every Checkout Session on the whole account: Payment Links,
Dashboard-created sessions, and even the Stripe CLI's own `stripe trigger` command (which the
design doc itself recommends running for manual testing). Without the guard, any of those would
throw, causing Stripe to retry a permanently-failing delivery for days. This was a real Important
finding from the final review — not a hypothetical.

**Q: What happens if the same webhook event is delivered twice? (Stripe explicitly guarantees
at-least-once, not exactly-once, delivery.)**
A: Every write is an idempotent upsert — find-by-Stripe-ID, then update (or find-none, then
create). Processing `checkout.session.completed` twice for the same session just sets the same
fields to the same values twice; `customer.subscription.deleted` twice just sets `CANCELED` twice.
No code path assumes an event arrives exactly once.

**Q: What's the actual bug that was found in `EventDataObjectDeserializer.getObject()`, and why
didn't the test suite catch it?**
A: `getObject()` silently returns an empty `Optional` — not an exception, not a parse error — the
instant the event's `api_version` field doesn't exactly equal the SDK's own compiled-in version
constant. Since Stripe stamps events with the *account's* configured API version (which has no
reason to match a specific SDK build's hardcoded string), this was empty on real traffic
essentially always. It slipped past every task-level test because those tests built their event
fixtures with `com.stripe.net.ApiResource.GSON`, and it was natural to leave `api_version` unset
or match it to whatever the SDK expected — the fixtures were unintentionally the one case that
*wouldn't* trigger the bug. It only surfaced when a final review asked "what happens with a
*realistic* mismatched version" and tested that directly.

### Billing/product logic

**Q: Why does a canceled subscriber immediately lose access instead of keeping it until the
period they already paid for ends?**
A: This wasn't special-cased — it's a side effect of how the quota's monotonic counter already
worked (see `workflow-run-quota.md`). The counter never resets, so the moment `status` flips off
`ACTIVE`, `effectiveLimit` falls back to the free tier's 10. If they'd already used more than 10,
they're blocked immediately. This matches the stated "no refunds on cancel" design, and required
zero additional cancellation-specific code — a good example of a design choice from an earlier
feature paying off for a later one it wasn't originally built for.

**Q: Why is checkout rejected with `409` for a user who already has an `ACTIVE` subscription, but
not for one who's `PAST_DUE`?**
A: That's a deliberate spec choice, not an implementation gap — flagged explicitly in review and
left as-is. A `PAST_DUE` subscriber failing payment can start a fresh checkout and end up with two
Stripe subscriptions; the old one's eventual `customer.subscription.deleted` gets logged as
"unrecognized" and ignored once the row's pointed at the new one. Low blast radius (a `past_due`
subscription is already failing and Stripe's own dunning will cancel it eventually), and fixing it
would mean designing a real upgrade/resume flow, which is explicitly out of scope for this
change.

**Q: How would you add a 4th plan tier without a code deploy?**
A: You can't fully avoid a deploy — `SubscriptionPlan` is a Java enum, and adding a plan means
adding an enum constant plus wiring its Price ID/limit through `StripePlanProperties`/
`PlanLimits`. What *is* deploy-free is changing a price for an *existing* plan: Prices live in the
Stripe Dashboard, referenced by ID from config, so a price change is a Dashboard edit, not a code
change — that split was a deliberate scope decision from the design phase.
