# MCP Server — Phase 1 (Tools over Existing JWT Auth) — Design Spec

Date: 2026-08-25

## Goal

Give Flowmatic an MCP (Model Context Protocol) server so an MCP-aware AI client (Claude Desktop,
etc.) can manage a user's workflows conversationally instead of only through the web UI. This is
Phase 1 of a two-phase plan: get the MCP tool surface working end-to-end over the app's existing
JWT auth, deferring a full OAuth authorization flow (Phase 2) until this is proven. Primary driver:
hands-on MCP experience and a demoable, deployed artifact — not a fully-scoped multi-tenant product
feature yet.

## Approach

Add Spring AI's MCP Server Boot Starter (WebMVC / Streamable HTTP transport, matching the existing
`spring-boot-starter-webmvc` stack) to expose an MCP endpoint from the already-deployed Spring Boot
app on Render — no new service to stand up. Chosen over:

- A stdio-only local server: only runs on the developer's own machine, not demoable to anyone else.
- Hand-rolling the MCP protocol without Spring AI: unnecessary — the starter already handles
  JSON-RPC framing, tool schema generation from `@Tool` annotations, and transport wiring.

## Auth: long-lived MCP personal access token

The app's existing access token (`app.jwt.access-token-expiry-ms=900000`, 15 minutes) is too
short-lived to paste into a persistent client like Claude Desktop. Refresh tokens (7 days) aren't
designed to be used directly as bearer API credentials either.

Add one new token type, reusing the existing `JwtUtil` signing key and builder:

- New method `JwtUtil.generateMcpToken(String email)`, building a token with a 1-year expiry and a
  `"type": "mcp"` claim (existing tokens use `"type": "access"` / `"type": "refresh"`).
- New endpoint `POST /api/mcp-token`, behind normal auth (caller must already be logged in with a
  valid access token), returns this token once. The user pastes it into their MCP client config.
- `JwtAuthFilter` accepts tokens of type `mcp` on the MCP endpoint path, resolving to the same
  authenticated principal the rest of the app uses. Regular `access` tokens continue to work
  everywhere else, unchanged.

This token is an explicit stand-in for Phase 2's real OAuth authorization flow, not the end state.

## New code

New package `com.flowmatic.auth.workflow.mcp`:

- One `@Tool`-annotated class, `WorkflowMcpTools`, wrapping existing services — no new business
  logic added.
- Tools (Phase 1 MVP, thin wrappers over what already exists):
  - `list_workflows` → mirrors `WorkflowController` `GET /workflows`
  - `get_workflow(id)` → mirrors `GET /workflows/{id}`
  - `run_workflow(id)` → mirrors `POST /workflows/{id}/run` (`WorkflowRunController`)
  - `get_dashboard_summary` → wraps `DashboardService.summary(userId, now)`
- Each tool resolves the current user the same way the equivalent controller does (from the
  authenticated principal), so results are scoped to whoever's token is calling — no cross-user
  data leakage.

## Data flow

1. User calls `POST /api/mcp-token` once (while logged in normally) and pastes the returned token
   into their MCP client (e.g. Claude Desktop's custom connector settings) as a bearer credential.
2. Client sends `initialize` then `tools/list` — Spring AI's starter returns the tool schemas
   generated from the `@Tool` methods.
3. User asks something in natural language; the client decides to call a tool and sends
   `tools/call`.
4. The MCP endpoint validates the bearer token via the existing filter (extended for the `mcp`
   token type), resolves the user, and the tool method delegates to the existing service
   (`WorkflowService` / `DashboardService` / etc.) exactly as the REST controllers already do.
5. The result flows back as MCP tool-result content; the client turns it into a natural-language
   reply.

## Error handling

Each tool method catches exceptions from the underlying service (not found, not owned by this
user, validation errors) and returns an MCP error result (`isError: true`) with a clean message —
never a raw exception or stack trace surfaced to the client.

## Testing

- Unit tests for the tool wrapper methods, mocking the underlying services the same way existing
  controller tests do.
- Manual verification with the MCP Inspector (Anthropic's MCP dev tool) against the local app
  before deploying.
- Manual verification against the deployed Render URL, connected from Claude Desktop, confirming a
  full conversational round trip (list workflows → run one → see the dashboard summary reflect
  it).

## Explicitly out of scope (deferred to Phase 2 or later)

- OAuth 2.1 authorization flow (browser login popup, dynamic client registration, PKCE) — the real
  MCP-spec-recommended auth for multi-user remote servers. Phase 1 uses a manually-copied
  long-lived token instead.
- Multi-tenant self-serve onboarding polish. `POST /api/mcp-token` technically works for any
  logged-in user, but Phase 1 targets a single user (the developer) proving the concept — no UI
  around generating/managing this token is in scope.
- Additional tools beyond the 4 listed (`list_workflow_runs`, `get_run_detail`, workflow
  create/edit/delete via MCP) — natural Phase 1.5 follow-ups, not built now.
- Token revocation/rotation for the MCP token — if it leaks, it's valid until the shared JWT
  secret is rotated (same blast radius as today's tokens).