# Frontend Guide: Manually Testing the Stripe Checkout Flow

**Status:** Same branch as `credits-and-plans.md` — `worktree-stripe-subscription-billing`,
**not yet merged to `main`**. Point your local frontend dev server at a backend instance running
this branch to test against.

This is a step-by-step for exercising the real checkout loop end-to-end — real (test-mode) Stripe
Checkout page, a real webhook delivery, real state change in the DB — so you can confirm your
pricing page, redirect routes, and usage indicator behave correctly against actual responses,
not fixtures.

## What you need

| Thing | Where to get it |
|---|---|
| Backend running on this branch | Ask backend to run it, or run it yourself: `PORT=8081 ./mvnw spring-boot:run` from the branch checkout |
| Stripe CLI, logged in | `brew install stripe/stripe-cli/stripe`, then `stripe login` — ask backend for access to the right Stripe account first |
| A verified test user (email + password) | Register via `POST /api/auth/register`, then verify the OTP emailed to you via `POST /api/auth/verify-email` — or ask backend for an existing verified test account |
| Stripe test card | `4242 4242 4242 4242`, any future expiry, any 3-digit CVC — [more scenarios](https://docs.stripe.com/testing) |

Everything below runs against **Stripe test mode** — no real money moves, and it never will as
long as the account's test/live toggle stays on test.

## 1. Start webhook forwarding

In its own terminal, leave this running for the whole session:

```bash
stripe listen --forward-to localhost:8081/api/billing/webhook
```

It prints a `whsec_...` signing secret. The backend's `.env` needs `STRIPE_WEBHOOK_SECRET` set to
this value — check with backend if you're not running the server yourself.

## 2. Get a JWT

```bash
curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<your-test-email>","password":"<your-test-password>"}'
```

Copy `accessToken` from the response — every call below needs it as `Authorization: Bearer <token>`.
It expires in 15 minutes; log in again if it goes stale mid-session.

## 3. Check the usage baseline

```bash
curl -s http://localhost:8081/api/workflows/runs/usage -H "Authorization: Bearer <TOKEN>"
```

A fresh free-tier user shows `{ "used": 0, "limit": 10, "remaining": 10, "unlimited": false, "plan": null }`.
This is exactly the shape your usage indicator renders — see `credits-and-plans.md`'s
"Endpoint 1 — Check usage" section for the rendering rules.

## 4. Create a checkout session

This is the call your pricing page's "Subscribe" button makes:

```bash
curl -s -X POST http://localhost:8081/api/billing/checkout-session \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"plan":"ESSENTIALS"}'
```

(`plan` is `"ESSENTIALS"`, `"PRO"`, or `"ENTERPRISE"`.) Copy `checkoutUrl` from the response — in
your real UI this is where you'd do `window.location.href = checkoutUrl`.

## 5. Complete checkout in the browser

Open `checkoutUrl` directly. Pay with `4242 4242 4242 4242` / any future expiry / any CVC.

Stripe redirects you to `/billing/success` (or `/billing/cancel` if you back out) afterward —
**this is your own frontend route**, so if nothing is running at that URL yet you'll just see a
browser error page. That's expected at this stage; it's not a backend problem. This is exactly the
moment to check your `/billing/success` page's logic: **the redirect fires before the webhook has
necessarily been processed**, so the page should poll `GET /api/workflows/runs/usage` for a few
seconds rather than assume the plan is active immediately — see `credits-and-plans.md`'s
"After checkout: the two redirect routes you must build" section.

## 6. Confirm the webhook landed

Back in the `stripe listen` terminal, you should see:

```
--> checkout.session.completed [evt_...]
<--  [200] POST http://localhost:8081/api/billing/webhook [evt_...]
```

A non-200 here means the plan won't activate — flag it to backend rather than assuming it's a
frontend bug.

## 7. Confirm the plan activated

Re-run step 3's usage call. It should now show the purchased plan:

```json
{ "used": 0, "limit": 100, "remaining": 100, "unlimited": false, "plan": "ESSENTIALS" }
```

This confirms the whole loop — Checkout → webhook → DB → usage endpoint — actually works, which is
what your `/billing/success` polling is waiting on.

## 8. Try the blocked/upgrade states (optional)

- Call `POST /api/billing/checkout-session` again with any plan while already subscribed → `409`.
  Confirm your pricing page shows "you already have an active plan" instead of retrying checkout.
- Run workflows past the limit → `POST /api/workflows/{id}/run` returns `402` once `remaining` hits
  0. Confirm your blocked-state UI triggers off this, not just off the usage indicator.

## Cleaning up

Stop `stripe listen` (`Ctrl+C`) and the backend process when you're done. Nothing here touches
real money or a production database if you're pointed at a test-mode Stripe account and a
dev/test DB — confirm that with backend if you're unsure which you're pointed at.
