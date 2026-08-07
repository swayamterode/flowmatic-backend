# Dashboard API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Back the dashboard UI's 4 KPI cards + 4 charts with real data by adding a
`com.flowmatic.auth.workflow.dashboard` package exposing `/api/dashboard/*` endpoints over the
existing `Workflow`/`WorkflowRun` domain model.

**Architecture:** `DashboardController` → `DashboardService` (aggregation + delta math) →
`WorkflowRunRepository`. Aggregation fetches bounded raw rows per request window and
buckets/aggregates/computes medians in Java (MySQL has no `PERCENTILE_CONT`/`date_trunc`, and this
keeps queries portable to the H2 test profile).

**Tech Stack:** Spring Boot, Java 17 records for DTOs, MySQL (H2 for tests).

**Spec:** `docs/superpowers/specs/2026-08-08-dashboard-api-design.md` (supersedes the unimplemented
2026-08-01 spec — this plan only covers the 4 KPI cards + 4 charts, no busiest-workflows,
recent-executions, activity feed, or `Workflow.status`).

## Global Constraints

- No Flyway — schema changes go through Hibernate `ddl-auto=update`. Any new column added to an
  already-populated table (`workflow_runs`) MUST be nullable at the DB level (no `nullable = false`)
  — MySQL's strict mode rejects an `ALTER TABLE ... ADD COLUMN ... NOT NULL` with no default against
  a non-empty table. Application code fills the field for every new row it writes; legacy rows read
  back `null`.
- DB is MySQL — no `PERCENTILE_CONT`, no `date_trunc`. All bucketing/median math happens in Java over
  a bounded, already-fetched list.
- All timestamps are `java.time.Instant`. "Today" = the UTC calendar day (no user-timezone concept
  exists anywhere in this codebase).
- No `@PreAuthorize` anywhere in this codebase — every endpoint resolves the caller manually via
  `currentUser.requireUserId(authentication)` (JWT subject = email) and does its own scoping.
- No `ApiResponse<T>` wrapper — controllers return `ResponseEntity<T>` with the DTO/list directly.
- Tests run under `@ActiveProfiles("test")` (H2, `ddl-auto=create-drop`). Any `@SpringBootTest` in
  this codebase needs `@MockitoBean JavaMailSender mailSender` — the real bean needs live SMTP config
  that isn't present in tests.
- Format with `./mvnw spotless:apply` before every commit (Spotless + Google Java Format runs on
  `verify` and via a pre-commit hook — an un-formatted commit will fail the hook).
- `application.properties` has NO defaults for `JWT_SECRET`, `GROQ_API_KEY`, `MAIL_USERNAME`,
  `MAIL_PASSWORD` — the `test` profile only overrides the datasource, so every `@SpringBootTest`
  needs these set in the shell env even though nothing in this feature touches JWT/AI/mail directly.
  Prefix every Maven test command with real values, e.g.:
  `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=...`

---

### Task 1: `TriggerType`/`ErrorCause` on `WorkflowRun` + classification in `WorkflowExecutionService`

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/entity/TriggerType.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/entity/ErrorCause.java`
- Modify: `src/main/java/com/flowmatic/auth/workflow/entity/WorkflowRun.java`
- Modify: `src/main/java/com/flowmatic/auth/workflow/execution/WorkflowExecutionService.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/execution/WorkflowExecutionServiceClassifyErrorTest.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/execution/WorkflowTriggerAndErrorCauseIntegrationTest.java`

**Interfaces:**
- Produces: `TriggerType{MANUAL}`, `ErrorCause{TIMEOUT, AUTH, VALIDATION, OTHER}` enums (package
  `com.flowmatic.auth.workflow.entity`); `WorkflowRun.getTriggerType()`/`getErrorCause()` (nullable);
  package-private `WorkflowExecutionService.classifyError(String)` returning `ErrorCause`. Tasks 2-4
  read `WorkflowRun.getTriggerType()`/`getErrorCause()`.

- [ ] **Step 1: Write the failing unit tests for `classifyError`**

Every `NodeExecutor` already catches its own exceptions and returns a message string (see spec §
Scope decision 2), so `classifyError` takes the final composed message, not an exception.

```java
package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.entity.ErrorCause;
import org.junit.jupiter.api.Test;

class WorkflowExecutionServiceClassifyErrorTest {

  @Test
  void classifiesNotConnectedMessageAsAuth() {
    assertThat(
            WorkflowExecutionService.classifyError(
                "Drive source failed: Google Drive is not connected"))
        .isEqualTo(ErrorCause.AUTH);
  }

  @Test
  void classifiesReconnectMessageAsAuth() {
    assertThat(
            WorkflowExecutionService.classifyError(
                "Drive source failed: Google access token expired and no refresh token is stored;"
                    + " reconnect required"))
        .isEqualTo(ErrorCause.AUTH);
  }

  @Test
  void classifiesTimeoutMessageAsTimeout() {
    assertThat(WorkflowExecutionService.classifyError("HTTP request failed: Read timed out"))
        .isEqualTo(ErrorCause.TIMEOUT);
  }

  @Test
  void classifiesOtherMessagesAsOther() {
    assertThat(WorkflowExecutionService.classifyError("Email node requires config 'to'"))
        .isEqualTo(ErrorCause.OTHER);
  }

  @Test
  void classifiesNullMessageAsOther() {
    assertThat(WorkflowExecutionService.classifyError(null)).isEqualTo(ErrorCause.OTHER);
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowExecutionServiceClassifyErrorTest`
Expected: compile error — `ErrorCause` and `classifyError` don't exist yet.

- [ ] **Step 3: Create the `TriggerType` enum**

```java
package com.flowmatic.auth.workflow.entity;

/**
 * How a {@link WorkflowRun} was started. Only {@code MANUAL} is real today — no scheduler or
 * webhook trigger path exists yet. Reserved as an enum (not a native MySQL enum) so extending the
 * vocabulary later never requires a schema migration.
 */
public enum TriggerType {
  MANUAL
}
```

- [ ] **Step 4: Create the `ErrorCause` enum**

```java
package com.flowmatic.auth.workflow.entity;

/**
 * Coarse classification of why a {@link WorkflowRun} failed, for the dashboard's failures-by-cause
 * breakdown. Classified from the final error message text in {@code WorkflowExecutionService} —
 * every {@code NodeExecutor} already flattens its own exceptions into a message before a {@code
 * WorkflowRun} ever sees them, so message content is the only signal left by the time a failure is
 * persisted.
 */
public enum ErrorCause {
  TIMEOUT,
  AUTH,
  VALIDATION,
  OTHER
}
```

- [ ] **Step 5: Add the two fields to `WorkflowRun`**

Modify `src/main/java/com/flowmatic/auth/workflow/entity/WorkflowRun.java` — add after the existing
`completedAt` field (currently line 35, right before the closing `}`):

```java
  // Nullable at the DB level even though every new run sets one of these: adding a NOT NULL column
  // via Hibernate ddl-auto=update fails against an already-populated table. Legacy rows read back
  // null and are treated as "predates this field", not an error.
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", columnDefinition = "varchar(32)")
  private TriggerType triggerType;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_cause", columnDefinition = "varchar(32)")
  private ErrorCause errorCause;
```

- [ ] **Step 6: Run the classify-error tests again to confirm they now pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowExecutionServiceClassifyErrorTest`
Expected: still FAILS — `classifyError` method itself doesn't exist on `WorkflowExecutionService`
yet (Step 5 only added the entity fields). Continue to Step 7.

- [ ] **Step 7: Write the failing integration test for stamping/classification**

```java
package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.ErrorCause;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowTriggerAndErrorCauseIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowExecutionService executionService;

  @Test
  void enqueueStampsManualTriggerType() {
    Workflow workflow = saveWorkflow(newUser("trigger@example.com"), "{\"nodes\":[],\"edges\":[]}");

    WorkflowRun run = executionService.enqueue(workflow.getId());

    assertThat(run.getTriggerType()).isEqualTo(TriggerType.MANUAL);
  }

  @Test
  void invalidGraphJsonMarksRunFailedWithValidationCause() {
    Workflow workflow = saveWorkflow(newUser("badgraph@example.com"), "not-json");

    WorkflowRun run = executionService.enqueue(workflow.getId());
    WorkflowRun executed = executionService.execute(run);

    assertThat(executed.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
    assertThat(executed.getErrorCause()).isEqualTo(ErrorCause.VALIDATION);
  }

  @Test
  void nodeConfigFailureMarksRunFailedWithOtherCause() {
    // A single OUTPUT node with no config: EmailOutputNodeExecutor returns
    // NodeExecutionResult.failure("Email node requires config 'to'") without throwing, exercising
    // the node-failure branch (not the outer catch) — the common real-world path.
    Workflow workflow =
        saveWorkflow(
            newUser("emailcfg@example.com"),
            "{\"nodes\":[{\"id\":\"e\",\"type\":\"OUTPUT\",\"data\":{}}],\"edges\":[]}");

    WorkflowRun run = executionService.enqueue(workflow.getId());
    WorkflowRun executed = executionService.execute(run);

    assertThat(executed.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
    assertThat(executed.getErrorCause()).isEqualTo(ErrorCause.OTHER);
  }

  private User newUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String graphJson) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson(graphJson).build());
  }
}
```

- [ ] **Step 8: Run it to confirm it fails**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowTriggerAndErrorCauseIntegrationTest`
Expected: FAIL — `enqueue()` doesn't stamp `triggerType`, and nothing sets `errorCause`.

- [ ] **Step 9: Modify `WorkflowExecutionService`**

Add these imports near the top (alongside the existing `com.flowmatic.auth.workflow.entity.*`
imports):

```java
import com.flowmatic.auth.workflow.entity.ErrorCause;
import com.flowmatic.auth.workflow.entity.TriggerType;
```

and add `import java.util.Locale;` next to the other `java.util.*` imports.

Change `enqueue()` (currently lines 141-150) — add `.triggerType(TriggerType.MANUAL)` to the builder:

```java
  @Transactional
  public WorkflowRun enqueue(Long workflowId) {
    Workflow workflow =
        workflowRepository
            .findById(workflowId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
    quotaService.enforceQuota(workflow.getUser().getId());
    return workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(WorkflowRunStatus.PENDING)
            .triggerType(TriggerType.MANUAL)
            .build());
  }
```

Change the start of `execute()` (currently line 172) to also track the classified cause:

```java
    boolean failed = false;
    ErrorCause errorCause = null;
    try {
```

Change the node-failure branch (currently lines 235-242) to classify the message it already
composes:

```java
        } else {
          statusByNode.put(node.id(), NodeRunStatus.FAILED);
          nodeLog.setStatus(NodeRunStatus.FAILED);
          String errorMessage = composeError(result);
          nodeLog.setErrorMessage(errorMessage);
          nodeRunLogRepository.save(nodeLog);
          errorCause = classifyError(errorMessage);
          failed = true;
          break;
        }
```

Change the outer catch (currently lines 244-247) — bad `graph_json` is the one case where a real
exception type still survives untouched, so check that before falling back to message matching:

```java
    } catch (RuntimeException e) {
      log.error("Run {} failed to execute", runEntity.getId(), e);
      failed = true;
      errorCause = e instanceof IllegalArgumentException
          ? ErrorCause.VALIDATION
          : classifyError(e.getMessage());
    }
```

Change the final block (currently lines 249-251) to persist the cause:

```java
    runEntity.setStatus(failed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
    runEntity.setCompletedAt(Instant.now());
    if (failed) {
      runEntity.setErrorCause(errorCause != null ? errorCause : ErrorCause.OTHER);
    }
    return workflowRunRepository.save(runEntity);
  }
```

Add this method near `composeError` at the bottom of the class (package-private, not `private`, so
the unit test in the same package can call it directly):

```java
  /**
   * Classifies a run failure from its final composed error message. Every {@code NodeExecutor}
   * already catches its own exceptions and returns a message string (see the failing-branch call
   * site above), so the message is the only signal left by the time a failure reaches here — this
   * is a heuristic over human-readable text, not an exception-type check.
   */
  static ErrorCause classifyError(String message) {
    String lower = String.valueOf(message).toLowerCase(Locale.ROOT);
    if (lower.contains("not connected") || lower.contains("reconnect")) {
      return ErrorCause.AUTH;
    }
    if (lower.contains("timeout") || lower.contains("timed out")) {
      return ErrorCause.TIMEOUT;
    }
    return ErrorCause.OTHER;
  }
```

- [ ] **Step 10: Run both test classes to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowExecutionServiceClassifyErrorTest,WorkflowTriggerAndErrorCauseIntegrationTest`
Expected: PASS (8 tests)

- [ ] **Step 11: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/entity/TriggerType.java \
  src/main/java/com/flowmatic/auth/workflow/entity/ErrorCause.java \
  src/main/java/com/flowmatic/auth/workflow/entity/WorkflowRun.java \
  src/main/java/com/flowmatic/auth/workflow/execution/WorkflowExecutionService.java \
  src/test/java/com/flowmatic/auth/workflow/execution/WorkflowExecutionServiceClassifyErrorTest.java \
  src/test/java/com/flowmatic/auth/workflow/execution/WorkflowTriggerAndErrorCauseIntegrationTest.java
git commit -m "feat(workflow): stamp trigger type and classify failure cause on WorkflowRun"
```

---

### Task 2: Dashboard DTOs + `WorkflowRunRepository` query method

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/SummaryStatsDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/TimeSeriesPointDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ExecutionsOverTimeDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/TriggerBreakdownDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/FailureCauseBucketDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/DurationTrendDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/MedianRunDurationDTO.java`
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/DashboardOverviewDTO.java`
- Modify: `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/repository/DashboardRepositoryQueryTest.java`

**Interfaces:**
- Consumes: `WorkflowRun.getTriggerType()`/`getErrorCause()` (Task 1).
- Produces: all 8 DTO records listed above (exact field names given below);
  `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(Long userId, Instant since)`.
  Task 3 calls this exact repository method and constructs these exact DTOs; Task 4 returns them
  directly from controller methods.

- [ ] **Step 1: Create all 8 DTO records**

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/SummaryStatsDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

public record SummaryStatsDTO(
    long executionsToday,
    Double executionsTodayDeltaPct,
    Double successRatePct,
    Double successRateDeltaPp,
    long failedRuns,
    Double failedRunsDeltaPct,
    Double medianRunTimeSec,
    Double medianRunTimeDeltaPct) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/TimeSeriesPointDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

/** One day's execution count. {@code date} is an ISO calendar date, e.g. "2026-08-01". */
public record TimeSeriesPointDTO(String date, long count) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ExecutionsOverTimeDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

import java.util.List;

public record ExecutionsOverTimeDTO(List<TimeSeriesPointDTO> points, Double deltaPct) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/TriggerBreakdownDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

/** {@code schedulePct}/{@code webhookPct} always read 0.0 today — no scheduled or webhook trigger
 * path exists yet (see design spec § scope decision 1). */
public record TriggerBreakdownDTO(
    double manualPct, double schedulePct, double webhookPct, Double deltaPp) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/FailureCauseBucketDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

import com.flowmatic.auth.workflow.entity.ErrorCause;

/** One day's count of failures for one cause. {@code date} is an ISO calendar date. */
public record FailureCauseBucketDTO(String date, ErrorCause cause, long count) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/DurationTrendDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

/** {@code dayLabel} is a short display label, e.g. "Aug 1". {@code medianSec} is null for a day
 * with no completed runs. */
public record DurationTrendDTO(String dayLabel, Double medianSec) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/MedianRunDurationDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

import java.util.List;

public record MedianRunDurationDTO(List<DurationTrendDTO> points, Double deltaPct) {}
```

`src/main/java/com/flowmatic/auth/workflow/dashboard/dto/DashboardOverviewDTO.java`:
```java
package com.flowmatic.auth.workflow.dashboard.dto;

public record DashboardOverviewDTO(
    SummaryStatsDTO summary,
    ExecutionsOverTimeDTO executionsOverTime,
    TriggerBreakdownDTO executionsByTrigger,
    java.util.List<FailureCauseBucketDTO> failuresByCause,
    MedianRunDurationDTO medianRunDuration) {}
```

- [ ] **Step 2: Write the failing repository test**

```java
package com.flowmatic.auth.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class DashboardRepositoryQueryTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void findsRunsSinceGivenInstantOrderedNewestFirst() {
    User user = newUser("dashrepo@example.com");
    Workflow workflow = saveWorkflow(user);
    Instant now = Instant.now();

    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(WorkflowRunStatus.SUCCESS)
            .triggerType(TriggerType.MANUAL)
            .startedAt(now.minus(10, ChronoUnit.DAYS))
            .completedAt(now.minus(10, ChronoUnit.DAYS).plusSeconds(5))
            .build());
    WorkflowRun recent =
        workflowRunRepository.save(
            WorkflowRun.builder()
                .workflow(workflow)
                .status(WorkflowRunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .startedAt(now.minus(1, ChronoUnit.HOURS))
                .completedAt(now)
                .build());

    List<WorkflowRun> found =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            user.getId(), now.minus(1, ChronoUnit.DAYS));

    assertThat(found).extracting(WorkflowRun::getId).containsExactly(recent.getId());
  }

  @Test
  void excludesPendingRunsWithNoStartedAt() {
    User user = newUser("dashrepo-pending@example.com");
    Workflow workflow = saveWorkflow(user);
    workflowRunRepository.save(
        WorkflowRun.builder().workflow(workflow).status(WorkflowRunStatus.PENDING).build());

    List<WorkflowRun> found =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            user.getId(), Instant.now().minus(1, ChronoUnit.DAYS));

    assertThat(found).isEmpty();
  }

  private User newUser(String email) {
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
}
```

- [ ] **Step 3: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardRepositoryQueryTest`
Expected: compile error — the new repository method doesn't exist yet.

- [ ] **Step 4: Add the query method to `WorkflowRunRepository`**

Add to `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java` (needs a
new import `java.time.Instant`):

```java
  /**
   * Bounded fetch for dashboard aggregation — every widget buckets/aggregates this in Java. A null
   * {@code startedAt} (still-PENDING runs) never satisfies {@code >= since}, so those are excluded
   * automatically.
   */
  List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
      Long userId, Instant since);
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardRepositoryQueryTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/dto/ \
  src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java \
  src/test/java/com/flowmatic/auth/workflow/repository/DashboardRepositoryQueryTest.java
git commit -m "feat(dashboard): add dashboard DTOs and supporting repository query"
```

---

### Task 3: `DashboardService` — aggregation logic

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc`
  (Task 2); `WorkflowRun.getTriggerType()`/`getErrorCause()` (Task 1); all Task 2 DTOs.
- Produces: `DashboardService.summary(Long userId)`, `.executionsOverTime(Long userId, int days)`,
  `.executionsByTrigger(Long userId, int days)`, `.failuresByCause(Long userId, int days)`,
  `.medianRunDuration(Long userId, int days)`. Task 4 calls these exact method names/signatures.

- [ ] **Step 1: Write the failing tests**

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.ExecutionsOverTimeDTO;
import com.flowmatic.auth.workflow.dashboard.dto.FailureCauseBucketDTO;
import com.flowmatic.auth.workflow.dashboard.dto.MedianRunDurationDTO;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.dashboard.dto.TriggerBreakdownDTO;
import com.flowmatic.auth.workflow.entity.ErrorCause;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class DashboardServiceTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired DashboardService dashboardService;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void summaryCountsTodaysExecutionsAndFailures() {
    User user = newUser("stats@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now().minus(1, ChronoUnit.HOURS),
        Duration.ofSeconds(5), TriggerType.MANUAL, null);
    saveRun(workflow, WorkflowRunStatus.FAILED, Instant.now().minus(2, ChronoUnit.HOURS),
        Duration.ofSeconds(2), TriggerType.MANUAL, ErrorCause.OTHER);

    SummaryStatsDTO stats = dashboardService.summary(user.getId());

    assertThat(stats.executionsToday()).isEqualTo(2);
    assertThat(stats.failedRuns()).isEqualTo(1);
    assertThat(stats.successRatePct()).isEqualTo(50.0);
  }

  @Test
  void newUserWithNoRunsGetsNullRatesNotAnException() {
    User user = newUser("empty@example.com");

    SummaryStatsDTO stats = dashboardService.summary(user.getId());

    assertThat(stats.executionsToday()).isZero();
    assertThat(stats.successRatePct()).isNull();
    assertThat(stats.executionsTodayDeltaPct()).isNull();
  }

  @Test
  void executionsOverTimeBucketsByUtcDay() {
    User user = newUser("timeseries@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(1),
        TriggerType.MANUAL, null);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(1),
        TriggerType.MANUAL, null);

    ExecutionsOverTimeDTO result = dashboardService.executionsOverTime(user.getId(), 7);

    long todayCount =
        result.points().stream()
            .filter(p -> p.date().equals(LocalDate.now(ZoneOffset.UTC).toString()))
            .findFirst()
            .orElseThrow()
            .count();
    assertThat(todayCount).isEqualTo(2);
    assertThat(result.points()).hasSize(7);
  }

  @Test
  void executionsByTriggerIsAllManualToday() {
    User user = newUser("trigger@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(1),
        TriggerType.MANUAL, null);

    TriggerBreakdownDTO result = dashboardService.executionsByTrigger(user.getId(), 7);

    assertThat(result.manualPct()).isEqualTo(100.0);
    assertThat(result.schedulePct()).isEqualTo(0.0);
    assertThat(result.webhookPct()).isEqualTo(0.0);
  }

  @Test
  void executionsByTriggerExcludesLegacyRunsWithNullTriggerType() {
    User user = newUser("legacy-trigger@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(1), null, null);

    TriggerBreakdownDTO result = dashboardService.executionsByTrigger(user.getId(), 7);

    assertThat(result.manualPct()).isEqualTo(0.0);
    assertThat(result.deltaPp()).isNull();
  }

  @Test
  void failuresByCauseGroupsByDayAndCause() {
    User user = newUser("failures@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.FAILED, Instant.now(), Duration.ofSeconds(1),
        TriggerType.MANUAL, ErrorCause.VALIDATION);

    List<FailureCauseBucketDTO> buckets = dashboardService.failuresByCause(user.getId(), 10);

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).cause()).isEqualTo(ErrorCause.VALIDATION);
    assertThat(buckets.get(0).count()).isEqualTo(1);
  }

  @Test
  void failuresByCauseExcludesLegacyRunsWithNullErrorCause() {
    User user = newUser("legacy-failure@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.FAILED, Instant.now(), Duration.ofSeconds(1),
        TriggerType.MANUAL, null);

    List<FailureCauseBucketDTO> buckets = dashboardService.failuresByCause(user.getId(), 10);

    assertThat(buckets).isEmpty();
  }

  @Test
  void medianRunDurationComputesMiddleValue() {
    User user = newUser("duration@example.com");
    Workflow workflow = saveWorkflow(user);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(10),
        TriggerType.MANUAL, null);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(20),
        TriggerType.MANUAL, null);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now(), Duration.ofSeconds(30),
        TriggerType.MANUAL, null);

    MedianRunDurationDTO result = dashboardService.medianRunDuration(user.getId(), 7);

    double todayMedian =
        result.points().stream()
            .mapToDouble(p -> p.medianSec() == null ? 0.0 : p.medianSec())
            .max()
            .orElseThrow();
    assertThat(todayMedian).isEqualTo(20.0);
  }

  private User newUser(String email) {
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

  private WorkflowRun saveRun(
      Workflow workflow,
      WorkflowRunStatus status,
      Instant startedAt,
      Duration duration,
      TriggerType triggerType,
      ErrorCause errorCause) {
    return workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(status)
            .triggerType(triggerType)
            .errorCause(errorCause)
            .startedAt(startedAt)
            .completedAt(startedAt.plus(duration))
            .build());
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest`
Expected: compile error — `DashboardService` doesn't exist yet.

- [ ] **Step 3: Create `DashboardService`**

```java
package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.DurationTrendDTO;
import com.flowmatic.auth.workflow.dashboard.dto.ExecutionsOverTimeDTO;
import com.flowmatic.auth.workflow.dashboard.dto.FailureCauseBucketDTO;
import com.flowmatic.auth.workflow.dashboard.dto.MedianRunDurationDTO;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.dashboard.dto.TimeSeriesPointDTO;
import com.flowmatic.auth.workflow.dashboard.dto.TriggerBreakdownDTO;
import com.flowmatic.auth.workflow.entity.ErrorCause;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregation queries backing the {@code /api/dashboard/*} endpoints, scoped per caller. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

  private static final DateTimeFormatter DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d");

  private final WorkflowRunRepository workflowRunRepository;

  public DashboardService(WorkflowRunRepository workflowRunRepository) {
    this.workflowRunRepository = workflowRunRepository;
  }

  public SummaryStatsDTO summary(Long userId) {
    Instant now = Instant.now();
    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            userId, now.minus(14, ChronoUnit.DAYS));

    LocalDate today = utcDate(now);
    LocalDate yesterday = today.minusDays(1);

    long executionsToday = countOnDay(runs, today);
    long executionsYesterday = countOnDay(runs, yesterday);
    long failedToday = countOnDayWithStatus(runs, today, WorkflowRunStatus.FAILED);
    long failedYesterday = countOnDayWithStatus(runs, yesterday, WorkflowRunStatus.FAILED);

    Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);
    List<WorkflowRun> last7 =
        runs.stream().filter(r -> !r.getStartedAt().isBefore(sevenDaysAgo)).toList();
    List<WorkflowRun> prior7 =
        runs.stream().filter(r -> r.getStartedAt().isBefore(sevenDaysAgo)).toList();

    Double last7Rate = successRate(last7);
    Double prior7Rate = successRate(prior7);
    Double successRateDeltaPp =
        (last7Rate == null || prior7Rate == null) ? null : last7Rate - prior7Rate;

    Double last7Median = medianDurationSeconds(last7);
    Double prior7Median = medianDurationSeconds(prior7);

    return new SummaryStatsDTO(
        executionsToday,
        percentDelta(executionsToday, executionsYesterday),
        last7Rate,
        successRateDeltaPp,
        failedToday,
        percentDelta(failedToday, failedYesterday),
        last7Median,
        percentDelta(last7Median, prior7Median));
  }

  public ExecutionsOverTimeDTO executionsOverTime(Long userId, int days) {
    Instant now = Instant.now();
    Instant boundary = now.minus(days, ChronoUnit.DAYS);
    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            userId, boundary.minus(days, ChronoUnit.DAYS));

    Map<LocalDate, Long> countsByDay = new TreeMap<>();
    long currentTotal = 0;
    long priorTotal = 0;
    for (WorkflowRun run : runs) {
      LocalDate day = utcDate(run.getStartedAt());
      if (!run.getStartedAt().isBefore(boundary)) {
        countsByDay.merge(day, 1L, Long::sum);
        currentTotal++;
      } else {
        priorTotal++;
      }
    }

    LocalDate today = utcDate(now);
    List<TimeSeriesPointDTO> points = new ArrayList<>();
    for (int i = days - 1; i >= 0; i--) {
      LocalDate day = today.minusDays(i);
      points.add(new TimeSeriesPointDTO(day.toString(), countsByDay.getOrDefault(day, 0L)));
    }

    return new ExecutionsOverTimeDTO(points, percentDelta(currentTotal, priorTotal));
  }

  public TriggerBreakdownDTO executionsByTrigger(Long userId, int days) {
    Instant now = Instant.now();
    Instant boundary = now.minus(days, ChronoUnit.DAYS);
    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            userId, boundary.minus(days, ChronoUnit.DAYS));

    List<WorkflowRun> current = new ArrayList<>();
    List<WorkflowRun> prior = new ArrayList<>();
    for (WorkflowRun run : runs) {
      if (run.getTriggerType() == null) {
        continue;
      }
      (!run.getStartedAt().isBefore(boundary) ? current : prior).add(run);
    }

    Double currentPct = manualPct(current);
    Double priorPct = manualPct(prior);
    Double deltaPp = (currentPct == null || priorPct == null) ? null : currentPct - priorPct;

    return new TriggerBreakdownDTO(currentPct == null ? 0.0 : currentPct, 0.0, 0.0, deltaPp);
  }

  public List<FailureCauseBucketDTO> failuresByCause(Long userId, int days) {
    Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            userId, since);

    Map<String, Map<ErrorCause, Long>> byDateAndCause = new TreeMap<>();
    for (WorkflowRun run : runs) {
      if (run.getStatus() != WorkflowRunStatus.FAILED || run.getErrorCause() == null) {
        continue;
      }
      String date = utcDate(run.getStartedAt()).toString();
      byDateAndCause
          .computeIfAbsent(date, d -> new EnumMap<>(ErrorCause.class))
          .merge(run.getErrorCause(), 1L, Long::sum);
    }

    List<FailureCauseBucketDTO> buckets = new ArrayList<>();
    byDateAndCause.forEach(
        (date, causeCounts) ->
            causeCounts.forEach(
                (cause, count) -> buckets.add(new FailureCauseBucketDTO(date, cause, count))));
    return buckets;
  }

  public MedianRunDurationDTO medianRunDuration(Long userId, int days) {
    Instant now = Instant.now();
    Instant boundary = now.minus(days, ChronoUnit.DAYS);
    List<WorkflowRun> runs =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            userId, boundary.minus(days, ChronoUnit.DAYS));

    Map<LocalDate, List<WorkflowRun>> byDay = new TreeMap<>();
    List<WorkflowRun> current = new ArrayList<>();
    List<WorkflowRun> prior = new ArrayList<>();
    for (WorkflowRun run : runs) {
      if (!isCompleted(run)) {
        continue;
      }
      if (!run.getStartedAt().isBefore(boundary)) {
        byDay.computeIfAbsent(utcDate(run.getStartedAt()), d -> new ArrayList<>()).add(run);
        current.add(run);
      } else {
        prior.add(run);
      }
    }

    LocalDate today = utcDate(now);
    List<DurationTrendDTO> points = new ArrayList<>();
    for (int i = days - 1; i >= 0; i--) {
      LocalDate day = today.minusDays(i);
      Double median = medianDurationSeconds(byDay.getOrDefault(day, List.of()));
      points.add(new DurationTrendDTO(DAY_LABEL_FORMAT.format(day), median));
    }

    return new MedianRunDurationDTO(
        points, percentDelta(medianDurationSeconds(current), medianDurationSeconds(prior)));
  }

  private static boolean isCompleted(WorkflowRun run) {
    return (run.getStatus() == WorkflowRunStatus.SUCCESS
            || run.getStatus() == WorkflowRunStatus.FAILED)
        && run.getStartedAt() != null
        && run.getCompletedAt() != null;
  }

  private static Double successRate(List<WorkflowRun> runs) {
    List<WorkflowRun> completed = runs.stream().filter(DashboardService::isCompleted).toList();
    if (completed.isEmpty()) {
      return null;
    }
    long successes =
        completed.stream().filter(r -> r.getStatus() == WorkflowRunStatus.SUCCESS).count();
    return successes * 100.0 / completed.size();
  }

  private static Double medianDurationSeconds(List<WorkflowRun> runs) {
    List<Double> durations =
        runs.stream()
            .filter(DashboardService::isCompleted)
            .map(r -> Duration.between(r.getStartedAt(), r.getCompletedAt()).toMillis() / 1000.0)
            .sorted()
            .toList();
    if (durations.isEmpty()) {
      return null;
    }
    int size = durations.size();
    return size % 2 == 1
        ? durations.get(size / 2)
        : (durations.get(size / 2 - 1) + durations.get(size / 2)) / 2.0;
  }

  private static Double manualPct(List<WorkflowRun> attributedRuns) {
    if (attributedRuns.isEmpty()) {
      return null;
    }
    long manual =
        attributedRuns.stream().filter(r -> r.getTriggerType() == TriggerType.MANUAL).count();
    return manual * 100.0 / attributedRuns.size();
  }

  private static long countOnDay(List<WorkflowRun> runs, LocalDate day) {
    return runs.stream().filter(r -> utcDate(r.getStartedAt()).equals(day)).count();
  }

  private static long countOnDayWithStatus(
      List<WorkflowRun> runs, LocalDate day, WorkflowRunStatus status) {
    return runs.stream()
        .filter(r -> r.getStatus() == status && utcDate(r.getStartedAt()).equals(day))
        .count();
  }

  private static Double percentDelta(long current, long previous) {
    return previous == 0 ? null : (current - previous) * 100.0 / previous;
  }

  private static Double percentDelta(Double current, Double previous) {
    if (current == null || previous == null || previous == 0.0) {
      return null;
    }
    return (current - previous) / previous * 100.0;
  }

  private static LocalDate utcDate(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest`
Expected: PASS (8 tests)

- [ ] **Step 5: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java \
  src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceTest.java
git commit -m "feat(dashboard): add DashboardService aggregation logic"
```

---

### Task 4: `DashboardController` — the 6 endpoints

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `DashboardService` (Task 3), `CurrentUser.requireUserId(Authentication)` (existing).
- Produces: `GET /api/dashboard/overview`, `/summary`, `/executions-over-time`,
  `/executions-by-trigger`, `/failures-by-cause`, `/median-run-duration`. Terminal task — nothing
  later depends on this.

- [ ] **Step 1: Write the failing integration test**

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
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

  @Test
  @WithMockUser(username = "dash-empty@example.com")
  void summaryReturnsZeroValuesForNewUserNotAnError() throws Exception {
    seedOwner("dash-empty@example.com");

    mockMvc
        .perform(get("/api/dashboard/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionsToday").value(0))
        .andExpect(jsonPath("$.failedRuns").value(0))
        .andExpect(jsonPath("$.successRatePct").value(nullValue()));
  }

  @Test
  @WithMockUser(username = "dash-overview@example.com")
  void overviewBundlesEveryWidget() throws Exception {
    User owner = seedOwner("dash-overview@example.com");
    Workflow workflow = seedWorkflow(owner);
    seedRun(workflow, WorkflowRunStatus.SUCCESS);

    mockMvc
        .perform(get("/api/dashboard/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.executionsToday").value(1))
        .andExpect(jsonPath("$.executionsOverTime.points").isArray())
        .andExpect(jsonPath("$.executionsByTrigger.manualPct").value(100.0))
        .andExpect(jsonPath("$.failuresByCause").isArray())
        .andExpect(jsonPath("$.medianRunDuration.points").isArray());
  }

  @Test
  @WithMockUser(username = "dash-days@example.com")
  void executionsOverTimeHonorsDaysParam() throws Exception {
    seedOwner("dash-days@example.com");

    mockMvc
        .perform(get("/api/dashboard/executions-over-time").param("days", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(3));
  }

  private User seedOwner(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow seedWorkflow(User owner) {
    return workflowRepository.save(
        Workflow.builder()
            .user(owner)
            .name("wf")
            .graphJson("{\"nodes\":[],\"edges\":[]}")
            .build());
  }

  private void seedRun(Workflow workflow, WorkflowRunStatus status) {
    Instant now = Instant.now();
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(status)
            .triggerType(TriggerType.MANUAL)
            .startedAt(now)
            .completedAt(now.plusSeconds(1))
            .build());
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerIntegrationTest`
Expected: compile error — `DashboardController` doesn't exist yet.

- [ ] **Step 3: Create `DashboardController`**

```java
package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.DashboardOverviewDTO;
import com.flowmatic.auth.workflow.dashboard.dto.ExecutionsOverTimeDTO;
import com.flowmatic.auth.workflow.dashboard.dto.FailureCauseBucketDTO;
import com.flowmatic.auth.workflow.dashboard.dto.MedianRunDurationDTO;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.dashboard.dto.TriggerBreakdownDTO;
import com.flowmatic.auth.workflow.web.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only aggregation endpoints backing the dashboard page, scoped to the caller's own workflows
 * via {@link CurrentUser#requireUserId}.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private final DashboardService dashboardService;
  private final CurrentUser currentUser;

  public DashboardController(DashboardService dashboardService, CurrentUser currentUser) {
    this.dashboardService = dashboardService;
    this.currentUser = currentUser;
  }

  /** Bundles every widget for initial page load; sub-widgets other than the time series use their
   * own endpoint's default window (7/10/7 days) rather than {@code days}. */
  @GetMapping("/overview")
  public ResponseEntity<DashboardOverviewDTO> overview(
      @RequestParam(defaultValue = "30") int days, Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(
        new DashboardOverviewDTO(
            dashboardService.summary(userId),
            dashboardService.executionsOverTime(userId, days),
            dashboardService.executionsByTrigger(userId, 7),
            dashboardService.failuresByCause(userId, 10),
            dashboardService.medianRunDuration(userId, 7)));
  }

  @GetMapping("/summary")
  public ResponseEntity<SummaryStatsDTO> summary(Authentication authentication) {
    return ResponseEntity.ok(dashboardService.summary(currentUser.requireUserId(authentication)));
  }

  @GetMapping("/executions-over-time")
  public ResponseEntity<ExecutionsOverTimeDTO> executionsOverTime(
      @RequestParam(defaultValue = "30") int days, Authentication authentication) {
    return ResponseEntity.ok(
        dashboardService.executionsOverTime(currentUser.requireUserId(authentication), days));
  }

  @GetMapping("/executions-by-trigger")
  public ResponseEntity<TriggerBreakdownDTO> executionsByTrigger(
      @RequestParam(defaultValue = "7") int days, Authentication authentication) {
    return ResponseEntity.ok(
        dashboardService.executionsByTrigger(currentUser.requireUserId(authentication), days));
  }

  @GetMapping("/failures-by-cause")
  public ResponseEntity<List<FailureCauseBucketDTO>> failuresByCause(
      @RequestParam(defaultValue = "10") int days, Authentication authentication) {
    return ResponseEntity.ok(
        dashboardService.failuresByCause(currentUser.requireUserId(authentication), days));
  }

  @GetMapping("/median-run-duration")
  public ResponseEntity<MedianRunDurationDTO> medianRunDuration(
      @RequestParam(defaultValue = "7") int days, Authentication authentication) {
    return ResponseEntity.ok(
        dashboardService.medianRunDuration(currentUser.requireUserId(authentication), days));
  }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerIntegrationTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the full test suite to confirm nothing else broke**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test`
Expected: PASS (every test, including pre-existing ones)

- [ ] **Step 6: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java \
  src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerIntegrationTest.java
git commit -m "feat(dashboard): add DashboardController with the 6 /api/dashboard endpoints"
```
