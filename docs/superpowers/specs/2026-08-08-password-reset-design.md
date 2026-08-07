# Password Reset — Design

**Status:** Design, not yet implemented
**Author:** Swayam Terode (with Claude Code)
**Date:** 2026-08-08

## What this is

Adds a standard "forgot password" flow to the existing auth API: a user who knows their account
email but not their password can request a reset link by email, click it, and set a new password
— without needing to be logged in.

Two new public endpoints:

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/forgot-password` | Request a reset link for an email |
| `POST /api/auth/reset-password` | Consume the token from that link and set a new password |

Plus a frontend integration doc so the frontend team can build the two pages (a "forgot password"
form and a "reset password" form) against this contract without needing to read the backend code.

## Why

There is currently no way for a user who forgets their password to regain access to their
account — the only self-serve account-recovery flow that exists is email verification (OTP) for
new registrations, which doesn't help a user who already has a verified account and lost their
password.

## Scope decisions made during design

- **Delivery mechanism: reset link with a random token, not a 6-digit OTP code.** The existing
  email-verification flow uses a human-typed 6-digit code, which fits a signup flow where the
  user is actively typing in a form anyway. Password reset is better served by an emailed link a
  frontend page can read a token from directly — this is what the OTP code precedent
  (`OtpService`/`EmailOtp`) does *not* fit well, so this feature gets its own entity and service
  rather than reusing that table.
- **Response to `forgot-password` never reveals whether the email has an account.** The response
  is always the same generic "if an account exists..." message, and no email is sent for unknown
  addresses. This is standard practice for password reset specifically (a common
  account-enumeration target), even though it's inconsistent with `resend-otp`'s current behavior
  of throwing "No account found for this email" — that inconsistency is accepted rather than
  fixed, since changing `resend-otp`'s behavior is out of scope here.
- **Token hash uses SHA-256, not BCrypt.** The raw token is 32 random bytes (256 bits of entropy),
  so — unlike a 6-digit OTP, which needs BCrypt's slow+salted hashing to resist brute-forcing a
  small keyspace — a deterministic fast hash is both sufficient and required: `reset-password`
  only receives the raw token (not the associated email), so the lookup must be an exact-match
  `findByTokenHash`, which BCrypt's salted output can't support.
- **Single-use, single-active-token-per-email.** Requesting a new reset link invalidates any
  previous unused one for that email (same upsert-by-email pattern `OtpServiceImpl.issueOtp`
  already uses), and the token row is deleted the moment it's successfully consumed.
- **No JWT revocation on reset.** This app's JWTs are stateless with no revocation store
  (`AuthServiceImpl.refreshToken` just decodes and checks the user still exists) — resetting a
  password does not invalidate already-issued access/refresh tokens; they remain valid until
  natural expiry (15 min access / 7 day refresh). Building revocation is a materially larger
  feature and explicitly deferred, not an oversight.

## Approaches considered

- **A — Dedicated `PasswordResetToken` entity/service (chosen).** Own table, own expiry/cooldown
  config, own exceptions. Slightly more new code than reusing the OTP table, but keeps two
  different security mechanisms (short human-typed code vs. long emailed token) from being
  forced into one schema and one set of semantics.
- **B — Reuse `EmailOtp`/`OtpService` for reset codes too.** Rejected: `EmailOtp` is
  unique-per-email with an `attempts` counter designed to rate-limit *guessing* a short code —
  none of that applies to a long random token, and a shared table risks a password-reset request
  clobbering a pending email-verification code (or vice versa) for the same address.
- **C — Reset via 6-digit OTP (email + code + new password), matching the existing pattern
  exactly.** Rejected per the delivery-mechanism decision above — worse UX for this specific flow,
  and the "exact match lookup" problem doesn't arise in this approach, but the UX cost wasn't
  worth avoiding it.

## Architecture

```
entity/
  PasswordResetToken.java     — id, email, tokenHash (SHA-256 hex), expiresAt, createdAt
repository/
  PasswordResetTokenRepository.java  — findByEmail, findByTokenHash
service/
  PasswordResetService.java          — interface
  impl/PasswordResetServiceImpl.java
exception/
  InvalidResetTokenException.java     — 400
  PasswordResetCooldownException.java — 429
dto/
  ForgotPasswordRequest.java   — { email }
  ResetPasswordRequest.java    — { token, newPassword }
```

`EmailService` gains `sendPasswordResetEmail(String to, String resetLink)`, reusing the existing
branded HTML shell from `EmailServiceImpl` (same card/wordmark/footer) with a link/button in place
of the code chip.

`AuthController` gains the two endpoints, delegating to `PasswordResetService`, following the
exact shape of its existing methods (`@Valid @RequestBody` → service call → `ResponseEntity.ok`).

New config (`application.properties`), same convention as `app.otp.*`:

```properties
app.password-reset.expiry-minutes=30
app.password-reset.resend-cooldown-seconds=60
app.frontend.reset-password-url=${FRONTEND_URL:http://localhost:5173}/reset-password
```

`SecurityConfig.PUBLIC_ENDPOINTS` gains `/api/auth/forgot-password` and `/api/auth/reset-password`
— both must be reachable by a logged-out user, same reasoning as `register`/`login`.

## Data flow

### Requesting a reset

1. `POST /api/auth/forgot-password` `{ "email": "..." }`.
2. `PasswordResetServiceImpl.requestReset(email)`:
   - `userRepository.findByEmail(email)` — if absent, do nothing further (skip straight to the
     generic response).
   - If present: upsert the `PasswordResetToken` row for this email (reuse existing row if one
     exists, same pattern as `OtpServiceImpl.issueOtp`) — but first check its `createdAt` against
     the cooldown window; if a row exists and is younger than
     `app.password-reset.resend-cooldown-seconds`, throw `PasswordResetCooldownException`. (This
     is only observable to someone who already knows the address has an account, since it
     requires having triggered one successful request already — it doesn't reopen the
     enumeration question the generic response is protecting.)
   - Generate `rawToken` = base64url(`SecureRandom` 32 bytes). Store `tokenHash =
     SHA-256(rawToken)` (hex), `expiresAt = now + expiry-minutes`, `createdAt = now`.
   - `emailService.sendPasswordResetEmail(email, app.frontend.reset-password-url + "?token=" +
     rawToken)` (`@Async`, matching `sendOtpEmail`).
3. Response: `200 { "message": "If an account exists for this email, a reset link has been
   sent." }` in all non-cooldown cases.

### Resetting the password

1. `POST /api/auth/reset-password` `{ "token": "...", "newPassword": "..." }`
   (`newPassword` validated `@Size(min=8, max=72)`, matching `RegisterRequest`).
2. `PasswordResetServiceImpl.resetPassword(token, newPassword)`:
   - `tokenHash = SHA-256(token)`; `repository.findByTokenHash(tokenHash)` — absent →
     `InvalidResetTokenException("Invalid or expired reset link.")`.
   - `expiresAt` in the past → delete the row, throw the same exception (expired and invalid are
     not distinguished in the message).
   - Otherwise: `userRepository.findByEmail(row.getEmail())` (should always resolve — the row was
     only ever created for a real user), set `passwordHash =
     passwordEncoder.encode(newPassword)`, save, delete the token row.
3. Response: `200 { "message": "Password reset successfully. You can now log in." }`.

## Error handling

| Condition | Status | Notes |
|---|---|---|
| `forgot-password` for unknown email | 200 | Generic success message, no email sent |
| `forgot-password` within cooldown window | 429 | `PasswordResetCooldownException` |
| `forgot-password` invalid email format | 400 | Standard `@Valid` validation |
| `reset-password` unknown/already-used token | 400 | `InvalidResetTokenException` |
| `reset-password` expired token | 400 | `InvalidResetTokenException` (same message as above) |
| `reset-password` `newPassword` too short/long | 400 | Standard `@Valid` validation |

Both new exceptions are wired into `GlobalExceptionHandler` following the existing
`InvalidOtpException`/`OtpResendCooldownException` pattern exactly (same `build(status, message,
req)` helper, same `ErrorResponse` shape).

## Test coverage

Following this codebase's existing test conventions (JUnit 5, Mockito via `mock()`/`@MockitoBean`,
H2 for integration tests under the `test` profile — see `SubscriptionServiceTest` and
`BillingControllerIntegrationTest` for the two styles already in use):

- `PasswordResetServiceTest` (unit, mocked repositories) — issues a token and emails a link for a
  known email; does nothing (no email, no row) for an unknown email; cooldown blocks a second
  request inside the window and allows one after it; expired token is rejected and deleted;
  valid token updates the password hash and deletes the row; a second attempt with the same
  (now-deleted) token is rejected.
- `AuthControllerPasswordResetIntegrationTest` (MockMvc + H2, `JavaMailSender` mocked via
  `@MockitoBean` as existing tests do) — full request/reset round trip against a real H2-backed
  user; validation errors (malformed email, short password) return 400 with the standard
  `ErrorResponse` shape; both endpoints are reachable without a JWT.

## Frontend deliverable

`docs/frontend/password-reset-integration.md` — an integration reference (contract-focused, not a
manual-testing walkthrough like `docs/frontend/testing-stripe-checkout.md`):

- Both endpoint contracts: request/response JSON, every status code from the error-handling table
  above.
- The expected page flow: a "forgot password" form (email only) → generic confirmation message
  shown regardless of outcome → user clicks the emailed link → frontend route
  `/reset-password?token=...` reads `token` from the query string → new-password form → `POST
  /api/auth/reset-password` → on success, redirect to the login page.
- Password validation rule to mirror client-side (8–72 characters, matching registration).
- A note that the reset link expires in 30 minutes and is single-use, so the frontend should
  surface `InvalidResetTokenException`'s message as "this link is invalid or has expired, request
  a new one" with a link back to the forgot-password form.

## Files to be touched

| File | What changes |
|---|---|
| `entity/PasswordResetToken.java` | **New.** |
| `repository/PasswordResetTokenRepository.java` | **New.** `findByEmail`, `findByTokenHash` |
| `service/PasswordResetService.java` | **New.** |
| `service/impl/PasswordResetServiceImpl.java` | **New.** |
| `exception/InvalidResetTokenException.java` | **New.** |
| `exception/PasswordResetCooldownException.java` | **New.** |
| `dto/ForgotPasswordRequest.java` | **New.** |
| `dto/ResetPasswordRequest.java` | **New.** |
| `service/EmailService.java` | Add `sendPasswordResetEmail` |
| `service/impl/EmailServiceImpl.java` | Implement it, new HTML template variant |
| `controller/AuthController.java` | Add `/forgot-password`, `/reset-password` |
| `exception/GlobalExceptionHandler.java` | Handle the two new exceptions |
| `security/SecurityConfig.java` | Add both new routes to `PUBLIC_ENDPOINTS` |
| `application.properties` | New `app.password-reset.*`, `app.frontend.reset-password-url` |
| `docs/frontend/password-reset-integration.md` | **New.** Frontend integration doc |

## What's explicitly NOT in this change

- No invalidation of existing JWT access/refresh tokens on reset (see scope decision above).
- No account lockout / rate limiting beyond the per-email resend cooldown (e.g. no IP-based
  throttling of `forgot-password`).
- No change to `resend-otp`'s existing enumeration-revealing behavior — noted as an inconsistency,
  not fixed here.
- No "change password while logged in" flow (that's a different, authenticated feature) — this is
  specifically for a user who can't log in at all.
