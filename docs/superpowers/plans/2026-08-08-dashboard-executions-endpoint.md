# Dashboard Executions-Over-Time Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/dashboard/executions-over-time?days={7|30|60}`, returning zero-filled
per-day execution counts across the caller's own workflows, in a new
`com.flowmatic.auth.workflow.dashboard` package.

**Architecture:** `DashboardController` → `DashboardService` (day-bucketing, zero-fill) →
`WorkflowRunRepository` (new derived-query method). One DTO record. No schema changes — this reads
existing `WorkflowRun` columns only.

**Tech Stack:** Spring Boot, Java 17 records, MySQL (H2 for tests), JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-08-dashboard-executions-endpoint-design.md`

## Global Constraints

- No `@PreAuthorize` anywhere in this codebase — resolve the caller manually via
  `currentUser.requireUserId(authentication)` (JWT subject = email) and scope queries yourself.
- No `ApiResponse<T>` wrapper — controllers return `ResponseEntity<T>` with the DTO/list directly.
- All timestamps are `java.time.Instant`. "Today" / day boundaries = the UTC calendar day (no
  user-timezone concept exists anywhere in this codebase).
- Tests run under `@ActiveProfiles("test")` (H2, `ddl-auto=create-drop`). Any `@SpringBootTest`
  needs `@MockitoBean JavaMailSender mailSender` — the real bean needs live SMTP config not present
  in tests.
- `application.properties` has no defaults for `JWT_SECRET`, `GROQ_API_KEY`, `MAIL_USERNAME`,
  `MAIL_PASSWORD` — every `@SpringBootTest` run needs these set in the shell env even though this
  feature touches none of them directly. Prefix every Maven test command:
  `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=...`
- Format with `./mvnw spotless:apply` before every commit (Spotless + Google Java Format runs on
  `verify` and via a pre-commit hook — an unformatted commit fails the hook).
- `days` must be exactly `7`, `30`, or `60` — any other value is a `400`, never silently clamped.
- Response is always zero-filled to exactly `days` entries, oldest day first — no gaps.

---

### Task 1: `WorkflowRunRepository` query method for a user's runs since a timestamp

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepositoryFindSinceTest.java`

**Interfaces:**
- Produces: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long userId, Instant since)` returning `List<WorkflowRun>`. Task 3 (`DashboardService`) calls this.

- [ ] **Step 1: Write the failing repository test**

```java
package com.flowmatic.auth.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunRepositoryFindSinceTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void findsOnlyTheGivenUsersRunsAtOrAfterSince() {
    User owner = saveUser("owner@example.com");
    User other = saveUser("other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);

    Instant since = Instant.parse("2026-08-01T00:00:00Z");
    saveRun(ownerWorkflow, since); // exactly at the boundary: included
    saveRun(ownerWorkflow, since.plus(1, ChronoUnit.DAYS)); // after: included
    saveRun(ownerWorkflow, since.minus(1, ChronoUnit.SECONDS)); // before: excluded
    saveRun(otherWorkflow, since.plus(1, ChronoUnit.DAYS)); // other user: excluded
    // PENDING run with null startedAt: excluded (never reached execute())
    workflowRunRepository.save(
        WorkflowRun.builder().workflow(ownerWorkflow).status(WorkflowRunStatus.PENDING).build());

    var found =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(
            owner.getId(), since);

    assertThat(found).hasSize(2);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  private void saveRun(Workflow workflow, Instant startedAt) {
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(WorkflowRunStatus.SUCCESS)
            .startedAt(startedAt)
            .completedAt(startedAt)
            .build());
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowRunRepositoryFindSinceTest`
Expected: compile error — `findByWorkflow_User_IdAndStartedAtGreaterThanEqual` doesn't exist yet.

- [ ] **Step 3: Add the derived query method**

In `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java`, add inside
the interface (alongside the existing derived methods):

```java
  /** A user's runs that have started, at or after {@code since} — for day-bucketing dashboards. */
  List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long userId, Instant since);
```

Add the import: `import java.time.Instant;`

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowRunRepositoryFindSinceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java src/test/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepositoryFindSinceTest.java
git commit -m "feat(workflow): add WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual"
```

---

### Task 2: `ExecutionRowDTO` record

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ExecutionRowDTO.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record ExecutionRowDTO(String date, long executions)`. Task 3 (`DashboardService`) constructs these; Task 4 (`DashboardController`) returns `List<ExecutionRowDTO>`.

This is a plain data-holder record with no logic — no test needed on its own (it's exercised end to
end by Task 3's and Task 4's tests).

- [ ] **Step 1: Create the DTO**

```java
package com.flowmatic.auth.workflow.dashboard.dto;

/** One point on the executions-over-time chart: a UTC calendar day and its run count. */
public record ExecutionRowDTO(String date, long executions) {}
```

- [ ] **Step 2: Compile check**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ExecutionRowDTO.java
git commit -m "feat(dashboard): add ExecutionRowDTO"
```

---

### Task 3: `DashboardService` — day-bucketing and zero-fill

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long, Instant)` (Task 1), `ExecutionRowDTO(String, long)` (Task 2).
- Produces: `DashboardService.executionsOverTime(Long userId, int days, Instant now)` returning `List<ExecutionRowDTO>`, exactly `days` entries, oldest first. The `now` parameter makes the "today" boundary explicit and testable (no hidden `Instant.now()` call to mock). Task 4 (`DashboardController`) calls this with `Instant.now()`.

- [ ] **Step 1: Write the failing unit tests**

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

  @Mock WorkflowRunRepository workflowRunRepository;

  private final DashboardService service = new DashboardService(workflowRunRepository);

  // "Now" is midday UTC on 2026-08-08, so "today" is unambiguously 2026-08-08 regardless of the
  // exact hour — avoids the test being sensitive to a boundary-of-day edge case it isn't testing.
  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

  @Test
  void zeroFillsEveryDayWhenNoRunsExist() {
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(1L, anyInstant()))
        .thenReturn(List.of());

    List<ExecutionRowDTO> rows = service.executionsOverTime(1L, 7, NOW);

    assertThat(rows).hasSize(7);
    assertThat(rows).allMatch(r -> r.executions() == 0);
    assertThat(rows.get(0).date()).isEqualTo("2026-08-02"); // oldest: today - 6
    assertThat(rows.get(6).date()).isEqualTo("2026-08-08"); // newest: today
  }

  @Test
  void countsMultipleRunsOnTheSameUtcDayTogether() {
    WorkflowRun a = runStartedAt(Instant.parse("2026-08-08T01:00:00Z"));
    WorkflowRun b = runStartedAt(Instant.parse("2026-08-08T23:00:00Z"));
    WorkflowRun c = runStartedAt(Instant.parse("2026-08-07T10:00:00Z"));
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(1L, anyInstant()))
        .thenReturn(List.of(a, b, c));

    List<ExecutionRowDTO> rows = service.executionsOverTime(1L, 7, NOW);

    ExecutionRowDTO aug8 = rows.stream().filter(r -> r.date().equals("2026-08-08")).findFirst().get();
    ExecutionRowDTO aug7 = rows.stream().filter(r -> r.date().equals("2026-08-07")).findFirst().get();
    assertThat(aug8.executions()).isEqualTo(2);
    assertThat(aug7.executions()).isEqualTo(1);
  }

  @Test
  void returnsSixtyEntriesFor60DayWindow() {
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(1L, anyInstant()))
        .thenReturn(List.of());

    List<ExecutionRowDTO> rows = service.executionsOverTime(1L, 60, NOW);

    assertThat(rows).hasSize(60);
  }

  private static Instant anyInstant() {
    return org.mockito.ArgumentMatchers.any();
  }

  private static WorkflowRun runStartedAt(Instant startedAt) {
    return WorkflowRun.builder()
        .workflow(Workflow.builder().build())
        .status(WorkflowRunStatus.SUCCESS)
        .startedAt(startedAt)
        .build();
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest`
Expected: compile error — `DashboardService` doesn't exist yet.

- [ ] **Step 3: Implement `DashboardService`**

```java
package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Aggregates {@link WorkflowRun} rows into per-UTC-day counts for the dashboard chart. */
@Service
public class DashboardService {

  private final WorkflowRunRepository workflowRunRepository;

  public DashboardService(WorkflowRunRepository workflowRunRepository) {
    this.workflowRunRepository = workflowRunRepository;
  }

  /**
   * Zero-filled per-day execution counts for {@code userId}'s workflows, oldest day first, over
   * the {@code days}-day window ending on {@code now}'s UTC calendar day (inclusive).
   */
  public List<ExecutionRowDTO> executionsOverTime(Long userId, int days, Instant now) {
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate startDay = today.minusDays(days - 1L);
    Instant since = startDay.atStartOfDay(ZoneOffset.UTC).toInstant();

    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(userId, since);

    Map<LocalDate, Long> countsByDay = new HashMap<>();
    for (WorkflowRun run : runs) {
      LocalDate day = run.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate();
      countsByDay.merge(day, 1L, Long::sum);
    }

    List<ExecutionRowDTO> result = new ArrayList<>();
    for (long offset = 0; offset < days; offset++) {
      LocalDate day = startDay.plusDays(offset);
      result.add(new ExecutionRowDTO(day.toString(), countsByDay.getOrDefault(day, 0L)));
    }
    return result;
  }
}
```

`ChronoUnit` import is unused by this implementation — omit it (or leave it out entirely; do not
import it).

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ExecutionRowDTO.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceTest.java
git commit -m "feat(dashboard): add DashboardService.executionsOverTime with zero-fill"
```

---

### Task 4: `DashboardController` — HTTP endpoint, validation, auth scoping

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `DashboardService.executionsOverTime(Long, int, Instant)` (Task 3), `CurrentUser.requireUserId(Authentication)` (existing, `com.flowmatic.auth.workflow.web.CurrentUser`).
- Produces: `GET /api/dashboard/executions-over-time?days={7|30|60}` → `200 OK` with `List<ExecutionRowDTO>` body, or `400` for an invalid `days`. Nothing later in this plan consumes this — it's the final task.

- [ ] **Step 1: Write the failing integration test**

This test hits the real HTTP layer via `MockMvc`, seeding data through repositories exactly like
`WorkflowTriggerAndErrorCauseIntegrationTest` does. Auth follows this codebase's existing
MockMvc-integration-test convention (see `WorkflowDeleteIntegrationTest`,
`WorkflowRunSendIntegrationTest`): `@WithMockUser(username = <email>)` fakes the `Authentication` —
it does **not** create a DB row — so a matching `User` row must be seeded before the request, since
`CurrentUser.requireUserId` resolves `authentication.getName()` via `UserRepository.findByEmail`
and throws if no such row exists. No real JWT is minted in any test in this codebase; do not add
one here.

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
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
class DashboardControllerIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  @Autowired ObjectMapper objectMapper;

  @Test
  @WithMockUser(username = "dash-empty@example.com")
  void returnsZeroFilledRowsForNewUserWithNoRuns() throws Exception {
    saveUser("dash-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-over-time?days=7"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<ExecutionRowDTO> rows =
        objectMapper.readValue(body, objectMapper.getTypeFactory().constructCollectionType(List.class, ExecutionRowDTO.class));
    assertThat(rows).hasSize(7);
    assertThat(rows).allMatch(r -> r.executions() == 0);
  }

  @Test
  @WithMockUser(username = "dash-owner@example.com")
  void countsOnlyTheCallersOwnRuns() throws Exception {
    User owner = saveUser("dash-owner@example.com");
    User other = saveUser("dash-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, Instant.now());
    saveRun(otherWorkflow, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-over-time?days=7"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<ExecutionRowDTO> rows =
        objectMapper.readValue(body, objectMapper.getTypeFactory().constructCollectionType(List.class, ExecutionRowDTO.class));
    long total = rows.stream().mapToLong(ExecutionRowDTO::executions).sum();
    assertThat(total).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "dash-badrange@example.com")
  void rejectsUnsupportedDaysValue() throws Exception {
    saveUser("dash-badrange@example.com");

    mockMvc.perform(get("/api/dashboard/executions-over-time?days=14")).andExpect(status().isBadRequest());
  }

  private User saveUser(String email) {
    return userRepository
        .findByEmail(email)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(email)
                        .fullName("Dash User")
                        .passwordHash("x")
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  private Workflow saveWorkflow(User user) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  private void saveRun(Workflow workflow, Instant startedAt) {
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(WorkflowRunStatus.SUCCESS)
            .startedAt(startedAt)
            .completedAt(startedAt)
            .build());
  }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerIntegrationTest`
Expected: compile error — `DashboardController` doesn't exist yet.

- [ ] **Step 3: Implement `DashboardController`**

```java
package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
import com.flowmatic.auth.workflow.web.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Backs the dashboard's executions-over-time chart. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private static final Set<Integer> SUPPORTED_DAYS = Set.of(7, 30, 60);

  private final DashboardService dashboardService;
  private final CurrentUser currentUser;

  public DashboardController(DashboardService dashboardService, CurrentUser currentUser) {
    this.dashboardService = dashboardService;
    this.currentUser = currentUser;
  }

  @GetMapping("/executions-over-time")
  public ResponseEntity<List<ExecutionRowDTO>> executionsOverTime(
      @RequestParam(defaultValue = "30") int days, Authentication authentication) {
    if (!SUPPORTED_DAYS.contains(days)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "days must be one of " + SUPPORTED_DAYS);
    }
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(dashboardService.executionsOverTime(userId, days, Instant.now()));
  }
}
```

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerIntegrationTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test`
Expected: BUILD SUCCESS, all tests pass (existing + the ones added in this plan)

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerIntegrationTest.java
git commit -m "feat(dashboard): add GET /api/dashboard/executions-over-time"
```
