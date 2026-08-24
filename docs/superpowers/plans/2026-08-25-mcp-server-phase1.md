# MCP Server Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose four read/write MCP tools (list/get/run workflows, dashboard summary) from the existing Spring Boot app, authenticated with a new long-lived personal-access-token type layered on the existing JWT system.

**Architecture:** Add Spring AI's MCP Server WebMVC starter to the already-deployed app (no new service). A new `mcp`-typed JWT (minted via a new `POST /api/mcp-token` endpoint) is accepted by the existing `JwtAuthFilter`, but only on the `/mcp` path. A new `WorkflowMcpTools` bean exposes `@McpTool`-annotated methods that are thin wrappers over the existing `WorkflowRepository` / `WorkflowExecutionService` / `DashboardService` — identical logic to what `WorkflowController` / `WorkflowRunController` / `DashboardController` already do.

**Tech Stack:** Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-bom` already imported), `spring-ai-starter-mcp-server-webmvc:2.0.0` (version-managed by the existing BOM import — do not pin a version in the dependency declaration), Java 17, JJWT 0.12.6, JUnit 5 + MockMvc + H2 (existing test stack).

**Spec:** `docs/superpowers/specs/2026-08-25-mcp-server-phase1-design.md`

## Global Constraints

- Dependency version: `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` — no explicit `<version>` tag; it's resolved from the `spring-ai-bom` (`2.0.0`) already imported in `pom.xml`.
- MCP tool annotation: `org.springframework.ai.mcp.annotation.McpTool` / `org.springframework.ai.mcp.annotation.McpToolParam` (NOT `org.springframework.ai.tool.annotation.Tool` — that's a different, unrelated annotation for LLM function-calling). `generateOutputSchema` defaults to `false` — do not set it explicitly.
- Default MCP endpoint path is `POST /mcp` (property `spring.ai.mcp.server.streamable-http.mcp-endpoint`, default already `/mcp` — do not override).
- New JWT token type string is `"mcp"` (existing types are `"access"` / `"refresh"` — see `JwtUtil.buildToken`).
- Never assert on Jackson deserializing this repo's Lombok DTOs (`AuthResponse`, `MessageResponse`, `McpTokenResponse`, etc.) into a typed object — they have only an all-args constructor (Lombok's `@Data` does not add an implicit no-arg constructor once `@AllArgsConstructor` is also present), so `objectMapper.readValue(json, SomeDto.class)` is not guaranteed to work. Parse response bodies as `Map.class` in tests instead (existing convention, e.g. `WorkflowController.fromJson`).
- Every `RuntimeException` thrown out of an `@McpTool` method (including `ResponseStatusException`, `IllegalArgumentException`) is automatically caught by the framework and converted into an MCP error result using `getMessage()` — do not add manual try/catch in tool methods; this is why `requireOwned`'s existing `ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found")` message style is safe to let propagate as-is.
- Test convention in this repo: `@SpringBootTest` + `@AutoConfigureMockMvc` (only when hitting HTTP) + `@ActiveProfiles("test")` + `@MockitoBean ResendEmailService resendEmailService` (required in every `@SpringBootTest` class, or the context fails to start) + `@WithMockUser(username = "...@example.com")` for a pre-authenticated caller. No plain-Mockito-unit-test style exists in this codebase for controller/service-adjacent code — follow the integration-test-over-H2 convention.

---

### Task 1: Add the MCP server dependency and confirm the endpoint is really wired up

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/flowmatic/auth/workflow/mcp/McpServerEndpointIntegrationTest.java`

**Interfaces:**
- Produces: a live `POST /mcp` endpoint (Spring-managed, no code of ours yet), protected by the existing security filter chain (unchanged in this task).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/flowmatic/auth/workflow/mcp/McpServerEndpointIntegrationTest.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.impl.ResendEmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpServerEndpointIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  @Test
  void mcpEndpointIsMappedAndReachableForAnAuthenticatedCaller() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-endpoint@example.com")
            .fullName("Endpoint User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
    String accessToken = jwtUtil.generateAccessToken("mcp-endpoint@example.com");

    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();

    // Not asserting a specific 2xx/4xx code for the malformed JSON-RPC body — only that the
    // request reached a real handler instead of falling through to Spring's default 404.
    assertThat(result.getResponse().getStatus()).isNotEqualTo(404);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=McpServerEndpointIntegrationTest`
Expected: FAIL — with no MCP dependency on the classpath, `/mcp` isn't mapped to any handler, so the response status is 404, failing the `isNotEqualTo(404)` assertion.

- [ ] **Step 3: Add the dependency and config**

In `pom.xml`, inside `<dependencies>`, add (right after the existing `spring-ai-starter-model-openai` dependency block):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

In `src/main/resources/application.properties`, append:

```properties
# ===== MCP Server (Phase 1 — personal-use tools over existing workflow/dashboard services) =====
spring.ai.mcp.server.name=flowmatic-mcp-server
spring.ai.mcp.server.version=0.1.0
spring.ai.mcp.server.instructions=Manage Flowmatic workflows: list them, inspect one, trigger a run, and check dashboard summary stats.
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=McpServerEndpointIntegrationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/flowmatic/auth/workflow/mcp/McpServerEndpointIntegrationTest.java
git commit -m "feat(mcp): add Spring AI MCP server dependency and base config"
```

---

### Task 2: Add a long-lived `mcp`-typed JWT to `JwtUtil`

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/security/JwtUtil.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/flowmatic/auth/security/JwtUtilMcpTokenTest.java`

**Interfaces:**
- Produces: `JwtUtil.generateMcpToken(String email)` → `String` (a JWT with `"type": "mcp"` claim, ~1-year expiry). Consumed by Task 3's `McpTokenController` and `JwtAuthFilter` change.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/flowmatic/auth/security/JwtUtilMcpTokenTest.java`:

```java
package com.flowmatic.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtUtilMcpTokenTest {

  private final JwtUtil jwtUtil =
      new JwtUtil(
          "0123456789012345678901234567890123456789", // dummy HS256 key, >= 32 bytes
          900_000L,
          604_800_000L,
          31_536_000_000L);

  @Test
  void generatesALongLivedMcpTypedToken() {
    String token = jwtUtil.generateMcpToken("mcp-user@example.com");

    assertThat(jwtUtil.isTokenValid(token)).isTrue();
    assertThat(jwtUtil.extractTokenType(token)).isEqualTo("mcp");
    assertThat(jwtUtil.extractEmail(token)).isEqualTo("mcp-user@example.com");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JwtUtilMcpTokenTest`
Expected: FAIL to compile — `JwtUtil` has no 4-arg constructor and no `generateMcpToken` method yet.

- [ ] **Step 3: Modify `JwtUtil.java`**

Replace lines 1-25 of `src/main/java/com/flowmatic/auth/security/JwtUtil.java` (package through the closing brace of the constructor) with:

```java
package com.flowmatic.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final SecretKey signingKey;
  private final long accessTokenExpiryMs;
  private final long refreshTokenExpiryMs;
  private final long mcpTokenExpiryMs;

  public JwtUtil(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
      @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs,
      @Value("${app.jwt.mcp-token-expiry-ms}") long mcpTokenExpiryMs) {
    this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
    this.accessTokenExpiryMs = accessTokenExpiryMs;
    this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    this.mcpTokenExpiryMs = mcpTokenExpiryMs;
  }
```

Then add this method right after `generateRefreshToken`:

```java
  public String generateMcpToken(String email) {
    return buildToken(email, mcpTokenExpiryMs, "mcp");
  }
```

In `src/main/resources/application.properties`, add this line right after `app.jwt.refresh-token-expiry-ms=604800000`:

```properties
# 1 year — long-lived so a Claude Desktop / MCP client connection doesn't need re-pasting.
app.jwt.mcp-token-expiry-ms=31536000000
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=JwtUtilMcpTokenTest`
Expected: PASS

Also run the full test suite once here to confirm the constructor-signature change didn't break anything relying on Spring's auto-wiring: `./mvnw test`
Expected: all PASS (the new `@Value` param is satisfied by the property just added).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/flowmatic/auth/security/JwtUtil.java src/main/resources/application.properties src/test/java/com/flowmatic/auth/security/JwtUtilMcpTokenTest.java
git commit -m "feat(mcp): add long-lived mcp-typed JWT to JwtUtil"
```

---

### Task 3: Scope `JwtAuthFilter` to accept `mcp` tokens only on `/mcp`, and add `POST /api/mcp-token`

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/security/JwtAuthFilter.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenResponse.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenController.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/mcp/McpTokenControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `JwtUtil.generateMcpToken(String)` from Task 2.
- Produces: `POST /api/mcp-token` (requires normal authentication) → `200 { "accessToken": "<jwt>", "tokenType": "Bearer" }`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/flowmatic/auth/workflow/mcp/McpTokenControllerIntegrationTest.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.impl.ResendEmailService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpTokenControllerIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "mcp-token-owner@example.com")
  void mintsAnMcpTypedTokenForTheLoggedInUser() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-token-owner@example.com")
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());

    String body =
        mockMvc
            .perform(post("/api/mcp-token"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
    assertThat(parsed.get("tokenType")).isEqualTo("Bearer");
    String token = (String) parsed.get("accessToken");
    assertThat(jwtUtil.extractTokenType(token)).isEqualTo("mcp");
    assertThat(jwtUtil.extractEmail(token)).isEqualTo("mcp-token-owner@example.com");
  }

  @Test
  void mcpTokenWorksOnMcpEndpointButNotOnRegularApiRoutes() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-scope@example.com")
            .fullName("Scope User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
    String mcpToken = jwtUtil.generateMcpToken("mcp-scope@example.com");

    MvcResult onMcpEndpoint =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + mcpToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();
    assertThat(onMcpEndpoint.getResponse().getStatus()).isNotEqualTo(401);

    mockMvc
        .perform(get("/api/workflows").header("Authorization", "Bearer " + mcpToken))
        .andExpect(status().isUnauthorized());
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=McpTokenControllerIntegrationTest`
Expected: FAIL to compile / 404 — `POST /api/mcp-token` doesn't exist yet, and `JwtAuthFilter` doesn't yet accept `mcp` tokens at all (so the first assertion in the second test would also fail: currently an `mcp`-typed token is rejected everywhere, including `/mcp`, giving 401 there too).

- [ ] **Step 3: Modify `JwtAuthFilter.java`**

Replace the whole file `src/main/java/com/flowmatic/auth/security/JwtAuthFilter.java` with:

```java
package com.flowmatic.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  private static final String HEADER_NAME = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  // mcp-typed tokens are scoped to only this path — a leaked MCP token can't be used against the
  // rest of the REST API the way a leaked access token could.
  private static final String MCP_PATH_PREFIX = "/mcp";

  private final JwtUtil jwtUtil;
  private final CustomUserDetailsService userDetailsService;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String authHeader = request.getHeader(HEADER_NAME);

    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = authHeader.substring(BEARER_PREFIX.length());

    if (jwtUtil.isTokenValid(token) && isAcceptableTokenType(token, request)) {
      String email = jwtUtil.extractEmail(token);

      if (SecurityContextHolder.getContext().getAuthentication() == null) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authToken);
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean isAcceptableTokenType(String token, HttpServletRequest request) {
    String tokenType = jwtUtil.extractTokenType(token);
    if ("access".equals(tokenType)) {
      return true;
    }
    return "mcp".equals(tokenType) && request.getRequestURI().startsWith(MCP_PATH_PREFIX);
  }
}
```

- [ ] **Step 4: Create `McpTokenResponse.java`**

Create `src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenResponse.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class McpTokenResponse {

  private String accessToken;
  private String tokenType;
}
```

- [ ] **Step 5: Create `McpTokenController.java`**

Create `src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenController.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import com.flowmatic.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mints a long-lived personal access token for connecting an MCP client (e.g. Claude Desktop). */
@RestController
@RequestMapping("/api/mcp-token")
@RequiredArgsConstructor
public class McpTokenController {

  private final JwtUtil jwtUtil;

  @PostMapping
  public ResponseEntity<McpTokenResponse> generate(Authentication authentication) {
    String token = jwtUtil.generateMcpToken(authentication.getName());
    return ResponseEntity.ok(
        McpTokenResponse.builder().accessToken(token).tokenType("Bearer").build());
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -Dtest=McpTokenControllerIntegrationTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flowmatic/auth/security/JwtAuthFilter.java src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenResponse.java src/main/java/com/flowmatic/auth/workflow/mcp/McpTokenController.java src/test/java/com/flowmatic/auth/workflow/mcp/McpTokenControllerIntegrationTest.java
git commit -m "feat(mcp): scope mcp JWTs to /mcp and add token-minting endpoint"
```

---

### Task 4: Add `WorkflowMcpTools` (the 4 MCP tools)

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpTools.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpToolsIntegrationTest.java`

**Interfaces:**
- Consumes: `WorkflowRepository` (`findByUser_Id`, `findById` — existing), `WorkflowExecutionService.enqueue(Long)` → `WorkflowRun` (existing), `DashboardService.summary(Long, Instant)` → `SummaryStatsDTO` (existing), `CurrentUser.requireUserId(Authentication)` (existing).
- Produces: 4 `@McpTool`-annotated methods, auto-registered by the framework — nothing else depends on this class directly.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpToolsIntegrationTest.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowMcpToolsIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired WorkflowMcpTools workflowMcpTools;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("MCP Test User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String name) {
    return workflowRepository.save(
        Workflow.builder()
            .user(user)
            .name(name)
            .graphJson("{\"nodes\":[],\"edges\":[]}")
            .build());
  }

  @Test
  @WithMockUser(username = "mcp-tools-a@example.com")
  void listWorkflowsReturnsOnlyTheCallersWorkflows() {
    User owner = saveUser("mcp-tools-a@example.com");
    User other = saveUser("mcp-tools-b@example.com");
    saveWorkflow(owner, "owner-workflow");
    saveWorkflow(other, "other-workflow");

    List<Map<String, Object>> result = workflowMcpTools.listWorkflows();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("name")).isEqualTo("owner-workflow");
  }

  @Test
  @WithMockUser(username = "mcp-tools-c@example.com")
  void getWorkflowReturnsTheGraphForAnOwnedWorkflow() {
    User owner = saveUser("mcp-tools-c@example.com");
    Workflow workflow = saveWorkflow(owner, "graph-workflow");

    Map<String, Object> result = workflowMcpTools.getWorkflow(workflow.getId());

    assertThat(result.get("name")).isEqualTo("graph-workflow");
    assertThat(result).containsKey("graph");
  }

  @Test
  @WithMockUser(username = "mcp-tools-d@example.com")
  void getWorkflowThrowsNotFoundForAnotherUsersWorkflow() {
    saveUser("mcp-tools-d@example.com");
    User other = saveUser("mcp-tools-e@example.com");
    Workflow othersWorkflow = saveWorkflow(other, "not-yours");

    assertThatThrownBy(() -> workflowMcpTools.getWorkflow(othersWorkflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Workflow not found");
  }

  @Test
  @WithMockUser(username = "mcp-tools-f@example.com")
  void runWorkflowEnqueuesAPendingRun() {
    User owner = saveUser("mcp-tools-f@example.com");
    Workflow workflow = saveWorkflow(owner, "run-me");

    Map<String, Object> result = workflowMcpTools.runWorkflow(workflow.getId());

    assertThat(result.get("status")).isEqualTo(WorkflowRunStatus.PENDING);
  }

  @Test
  @WithMockUser(username = "mcp-tools-g@example.com")
  void getDashboardSummaryReturnsZerosForANewUserWithNoRuns() {
    saveUser("mcp-tools-g@example.com");

    var result = workflowMcpTools.getDashboardSummary();

    assertThat(result.executionsToday()).isEqualTo(0);
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=WorkflowMcpToolsIntegrationTest`
Expected: FAIL to compile — `WorkflowMcpTools` doesn't exist yet.

- [ ] **Step 3: Create `WorkflowMcpTools.java`**

Create `src/main/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpTools.java`:

```java
package com.flowmatic.auth.workflow.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.dashboard.DashboardService;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.execution.WorkflowExecutionService;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.web.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exposes workflow/dashboard actions as MCP tools, scoped to whichever user's token is calling.
 * Thin wrappers only — same repository/service calls {@code WorkflowController} / {@code
 * WorkflowRunController} / {@code DashboardController} already make.
 */
@Component
public class WorkflowMcpTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkflowRepository workflowRepository;
  private final WorkflowExecutionService executionService;
  private final DashboardService dashboardService;
  private final CurrentUser currentUser;

  public WorkflowMcpTools(
      WorkflowRepository workflowRepository,
      WorkflowExecutionService executionService,
      DashboardService dashboardService,
      CurrentUser currentUser) {
    this.workflowRepository = workflowRepository;
    this.executionService = executionService;
    this.dashboardService = dashboardService;
    this.currentUser = currentUser;
  }

  @McpTool(
      name = "list_workflows",
      description = "List the workflows owned by the current Flowmatic user.")
  public List<Map<String, Object>> listWorkflows() {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return workflowRepository.findByUser_Id(userId).stream().map(this::summary).toList();
  }

  @McpTool(
      name = "get_workflow",
      description = "Get a single workflow's details, including its node graph, by id.")
  public Map<String, Object> getWorkflow(
      @McpToolParam(description = "The workflow id", required = true) Long workflowId) {
    return detail(requireOwned(workflowId));
  }

  @McpTool(
      name = "run_workflow",
      description =
          "Enqueue a run of the given workflow. Runs execute one at a time; check "
              + "get_dashboard_summary afterwards to see the result.")
  public Map<String, Object> runWorkflow(
      @McpToolParam(description = "The workflow id to run", required = true) Long workflowId) {
    requireOwned(workflowId);
    WorkflowRun run = executionService.enqueue(workflowId);
    return runSummary(run);
  }

  @McpTool(
      name = "get_dashboard_summary",
      description =
          "Get aggregate execution stats for the current user: executions today, success "
              + "rate, failed runs, median run time.")
  public SummaryStatsDTO getDashboardSummary() {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return dashboardService.summary(userId, Instant.now());
  }

  private static Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  private Workflow requireOwned(Long id) {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return workflowRepository
        .findById(id)
        .filter(w -> w.getUser().getId().equals(userId))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
  }

  private Map<String, Object> summary(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private Map<String, Object> detail(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "graph", fromJson(w.getGraphJson()),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private Map<String, Object> runSummary(WorkflowRun run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.getId());
    m.put("status", run.getStatus());
    m.put("startedAt", String.valueOf(run.getStartedAt()));
    m.put("completedAt", String.valueOf(run.getCompletedAt()));
    return m;
  }

  private static Object fromJson(String json) {
    try {
      return MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      return json;
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=WorkflowMcpToolsIntegrationTest`
Expected: PASS

Then run the full suite to confirm nothing else regressed: `./mvnw test`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpTools.java src/test/java/com/flowmatic/auth/workflow/mcp/WorkflowMcpToolsIntegrationTest.java
git commit -m "feat(mcp): add list/get/run workflow and dashboard summary MCP tools"
```

---

### Task 5: Manual end-to-end verification (MCP Inspector, then Claude Desktop against the deployed Render URL)

**Files:** none — this task produces no code changes, only a verified working system. Do it after Task 4 is committed.

**Interfaces:** none (manual QA gate for Phase 1).

- [ ] **Step 1: Run the app locally**

```bash
./mvnw spring-boot:run
```

Confirm it starts without errors on `http://localhost:8080`.

- [ ] **Step 2: Log in and mint an MCP token**

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<your existing test account email>","password":"<your password>"}'
```

Copy the `accessToken` from the response, then:

```bash
curl -s -X POST http://localhost:8080/api/mcp-token \
  -H "Authorization: Bearer <accessToken from above>"
```

Copy the `accessToken` from this response — this is the long-lived MCP token.

- [ ] **Step 3: Verify with the MCP Inspector**

```bash
npx @modelcontextprotocol/inspector
```

Open the browser tab it starts (defaults to `http://localhost:6274`). In the connection form:
- Transport type: **Streamable HTTP**
- URL: `http://localhost:8080/mcp`
- Add an `Authorization` header with value `Bearer <mcp token from Step 2>`

Connect, open the Tools tab, and confirm all 4 tools (`list_workflows`, `get_workflow`, `run_workflow`, `get_dashboard_summary`) are listed. Invoke `list_workflows` and confirm it returns your account's real workflows (or an empty list if you have none yet — create one via the normal web UI first if you want to see real data). Then invoke `get_workflow` with an id that doesn't belong to you (or doesn't exist) and confirm the Inspector shows an error result with the message "Workflow not found" — this is the automatic exception-to-`isError` conversion described in the Global Constraints working end to end, not a raw stack trace.

- [ ] **Step 4: Deploy and verify against Claude Desktop**

Push the committed changes to `main` (existing Render deploy pipeline picks it up). Once live, repeat Steps 2-3 against `https://flowmatic-backend-3c9q.onrender.com` instead of `localhost:8080`, this time connecting from Claude Desktop's custom connector settings (Streamable HTTP, the deployed `/mcp` URL, `Authorization: Bearer <token>` header) instead of the Inspector. Confirm a natural-language round trip works: ask Claude to list your workflows, then run one, then check the dashboard summary.

- [ ] **Step 5: Note completion**

No commit needed (no files changed) — Phase 1 is done once Step 4's round trip works.