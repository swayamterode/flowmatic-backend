# Dashboard Executions-by-Status Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/dashboard/executions-by-status`, returning all 4 `WorkflowRunStatus` counts
(zero-filled) over the last 30 days, in the existing `com.flowmatic.auth.workflow.dashboard`
package.

**Architecture:** `DashboardController` → `DashboardService.executionsByStatus(...)` → the
*existing* `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual` method (added
for the executions-over-time endpoint) — no new repository method needed. One new DTO record. No
schema changes.

**Tech Stack:** Spring Boot, Java 17 records, MySQL (H2 for tests), JUnit 5 + AssertJ + Mockito +
MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-08-dashboard-executions-by-status-design.md`

## Global Constraints

- No `@PreAuthorize` — resolve the caller via `currentUser.requireUserId(authentication)`.
- No `ApiResponse<T>` wrapper — `ResponseEntity<T>` returned directly.
- All timestamps `java.time.Instant`; the 30-day window boundary is the UTC calendar day.
- The response is always exactly 4 entries, in `WorkflowRunStatus` declaration order
  (`PENDING, RUNNING, SUCCESS, FAILED`), never count-sorted, never omitting a zero-count status.
- Tests run under `@ActiveProfiles("test")` (H2). Any `@SpringBootTest` needs
  `@MockitoBean JavaMailSender mailSender`.
- Every `./mvnw test` run needs: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test`
- Format with `./mvnw spotless:apply` before every commit.
- Git safety: the working tree may contain unrelated uncommitted changes from other work (a
  Postman collection edit, and/or benign spotless-reformatting noise on
  `DotenvEnvironmentPostProcessor.java`). Every commit in this plan must stage and commit ONLY the
  exact files its own step names, via explicit `git add <path>` / `git commit <path> -m "..."` —
  never `git add -A`, `git add .`, or a bare `git commit` with no pathspec. Run `git status` before
  and after each commit to confirm nothing else was swept in.

---

### Task 1: `StatusBreakdownDTO` record

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/StatusBreakdownDTO.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record StatusBreakdownDTO(String status, long count)`. Task 2 (`DashboardService`) constructs these; Task 3 (`DashboardController`) returns `List<StatusBreakdownDTO>`.

- [ ] **Step 1: Create the DTO**

```java
package com.flowmatic.auth.workflow.dashboard.dto;

/** One slice of the dashboard's executions-by-status pie chart. */
public record StatusBreakdownDTO(String status, long count) {}
```

- [ ] **Step 2: Compile check**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/dto/StatusBreakdownDTO.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/dto/StatusBreakdownDTO.java -m "feat(dashboard): add StatusBreakdownDTO"
```

---

### Task 2: `DashboardService.executionsByStatus`

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceExecutionsByStatusTest.java`

**Interfaces:**
- Consumes: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long, Instant)` (existing), `StatusBreakdownDTO` (Task 1), `WorkflowRunStatus{PENDING,RUNNING,SUCCESS,FAILED}` (existing).
- Produces: `DashboardService.executionsByStatus(Long userId, Instant now)` returning `List<StatusBreakdownDTO>`, always exactly 4 entries in enum declaration order. Task 3 (`DashboardController`) calls this with `Instant.now()`.

This is a new method added to the existing `DashboardService` class (which already has
`executionsOverTime` and `summary`) — do not modify either of those methods. `WorkflowRunStatus` and
`Instant`/`LocalDate`/`ZoneOffset`/`List`/`ArrayList`/`Map`/`HashMap` are all already imported in
this file from prior work; the only new import is `StatusBreakdownDTO`.

- [ ] **Step 1: Write the failing unit tests**

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceExecutionsByStatusTest {

  @Mock WorkflowRunRepository workflowRunRepository;

  private DashboardService service;

  @BeforeEach
  void setUp() {
    service = new DashboardService(workflowRunRepository);
  }

  // "Now" is midday UTC on 2026-08-08; the 30-day window starts at 2026-07-09T00:00:00Z.
  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
  private static final Instant SINCE = Instant.parse("2026-07-09T00:00:00Z");

  private static WorkflowRun run(WorkflowRunStatus status) {
    return WorkflowRun.builder().workflow(Workflow.builder().build()).status(status).build();
  }

  @Test
  void zeroDataYieldsAllFourStatusesAtZero() {
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(1L, SINCE))
        .thenReturn(List.of());

    List<StatusBreakdownDTO> result = service.executionsByStatus(1L, NOW);

    assertThat(result)
        .containsExactly(
            new StatusBreakdownDTO("PENDING", 0),
            new StatusBreakdownDTO("RUNNING", 0),
            new StatusBreakdownDTO("SUCCESS", 0),
            new StatusBreakdownDTO("FAILED", 0));
  }

  @Test
  void countsGroupedByStatusInDeclarationOrder() {
    List<WorkflowRun> runs =
        List.of(
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.FAILED),
            run(WorkflowRunStatus.FAILED),
            run(WorkflowRunStatus.RUNNING));
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(1L, SINCE))
        .thenReturn(runs);

    List<StatusBreakdownDTO> result = service.executionsByStatus(1L, NOW);

    assertThat(result)
        .containsExactly(
            new StatusBreakdownDTO("PENDING", 0),
            new StatusBreakdownDTO("RUNNING", 1),
            new StatusBreakdownDTO("SUCCESS", 3),
            new StatusBreakdownDTO("FAILED", 2));
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceExecutionsByStatusTest`
Expected: compile error — `executionsByStatus` doesn't exist yet.

- [ ] **Step 3: Add the `executionsByStatus` method**

Add this import to the existing import block:

```java
import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
```

Add this method inside the `DashboardService` class, after the existing `summary` method (and its
private helpers) — do not modify `executionsOverTime` or `summary`:

```java
  /**
   * All 4 {@link WorkflowRunStatus} counts over the last 30 days, always exactly 4 entries in
   * enum declaration order — for the dashboard's executions-by-status pie chart.
   */
  public List<StatusBreakdownDTO> executionsByStatus(Long userId, Instant now) {
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    Instant since = today.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant();

    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(userId, since);

    Map<WorkflowRunStatus, Long> countsByStatus = new HashMap<>();
    for (WorkflowRun run : runs) {
      countsByStatus.merge(run.getStatus(), 1L, Long::sum);
    }

    List<StatusBreakdownDTO> result = new ArrayList<>();
    for (WorkflowRunStatus status : WorkflowRunStatus.values()) {
      result.add(new StatusBreakdownDTO(status.name(), countsByStatus.getOrDefault(status, 0L)));
    }
    return result;
  }
```

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceExecutionsByStatusTest`
Expected: PASS (2 tests). Also re-run `DashboardServiceTest` and `DashboardServiceSummaryTest` to
confirm the existing methods are unaffected:
`JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest,DashboardServiceSummaryTest`

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceExecutionsByStatusTest.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceExecutionsByStatusTest.java -m "feat(dashboard): add DashboardService.executionsByStatus"
```

---

### Task 3: `DashboardController` — `/api/dashboard/executions-by-status` endpoint

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerExecutionsByStatusIntegrationTest.java`

**Interfaces:**
- Consumes: `DashboardService.executionsByStatus(Long, Instant)` (Task 2), `CurrentUser.requireUserId(Authentication)` (existing).
- Produces: `GET /api/dashboard/executions-by-status` → `200 OK` with a `List<StatusBreakdownDTO>` body. Final task in this plan.

Adds a third `@GetMapping` to the existing `DashboardController` class (which already has
`/executions-over-time` and `/summary`) — do not modify either existing method.

- [ ] **Step 1: Write the failing integration test**

Auth follows this codebase's established convention: `@WithMockUser(username = <email>)` + a
separately-seeded matching `User` row; self-managed `ObjectMapper` (`new ObjectMapper()`), never
`@Autowired` (Jackson 2/3 mismatch on this stack) — see `DashboardControllerIntegrationTest` or
`DashboardControllerSummaryIntegrationTest` in this same package for the exact pattern.

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerExecutionsByStatusIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "status-empty@example.com")
  void returnsAllFourStatusesAtZeroForNewUserWithNoRuns() throws Exception {
    saveUser("status-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body, objectMapper.getTypeFactory().constructCollectionType(List.class, StatusBreakdownDTO.class));
    assertThat(result).hasSize(4);
    assertThat(result).allMatch(r -> r.count() == 0);
  }

  @Test
  @WithMockUser(username = "status-owner@example.com")
  void countsOnlyTheCallersOwnRuns() throws Exception {
    User owner = saveUser("status-owner@example.com");
    User other = saveUser("status-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(ownerWorkflow, WorkflowRunStatus.FAILED, Instant.now());
    saveRun(otherWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body, objectMapper.getTypeFactory().constructCollectionType(List.class, StatusBreakdownDTO.class));
    long success =
        result.stream().filter(r -> r.status().equals("SUCCESS")).findFirst().get().count();
    long failed =
        result.stream().filter(r -> r.status().equals("FAILED")).findFirst().get().count();
    assertThat(success).isEqualTo(2);
    assertThat(failed).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "status-old@example.com")
  void excludesRunsOlderThanThirtyDays() throws Exception {
    User owner = saveUser("status-old@example.com");
    Workflow workflow = saveWorkflow(owner);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now().minus(java.time.Duration.ofDays(45)));

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body, objectMapper.getTypeFactory().constructCollectionType(List.class, StatusBreakdownDTO.class));
    long success =
        result.stream().filter(r -> r.status().equals("SUCCESS")).findFirst().get().count();
    assertThat(success).isEqualTo(1);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Status User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  private void saveRun(Workflow workflow, WorkflowRunStatus status, Instant startedAt) {
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(status)
            .startedAt(startedAt)
            .completedAt(startedAt)
            .build());
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerExecutionsByStatusIntegrationTest`
Expected: compile error — there is no `/executions-by-status` mapping yet.

- [ ] **Step 3: Add the endpoint to `DashboardController`**

Add this import to the existing import block:

```java
import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
```

Add this method inside the existing `DashboardController` class, after the existing `summary`
method — do not modify either existing method:

```java
  @GetMapping("/executions-by-status")
  public ResponseEntity<List<StatusBreakdownDTO>> executionsByStatus(Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(dashboardService.executionsByStatus(userId, Instant.now()));
  }
```

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerExecutionsByStatusIntegrationTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerExecutionsByStatusIntegrationTest.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerExecutionsByStatusIntegrationTest.java -m "feat(dashboard): add GET /api/dashboard/executions-by-status"
```
