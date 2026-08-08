# Dashboard Summary Stats Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/dashboard/summary`, returning the 4 KPI-card stats (executions today,
success rate, failed runs, median run time) with day-over-day / week-over-week deltas, in the
existing `com.flowmatic.auth.workflow.dashboard` package.

**Architecture:** `DashboardController` → `DashboardService.summary(...)` (fetches 4 bounded
windows, computes counts/rate/median/deltas) → a new bounded-range `WorkflowRunRepository` method.
One new DTO record. No schema changes.

**Tech Stack:** Spring Boot, Java 17 records, MySQL (H2 for tests), JUnit 5 + AssertJ + Mockito +
MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-08-dashboard-summary-endpoint-design.md`

## Global Constraints

- No `@PreAuthorize` anywhere in this codebase — resolve the caller via
  `currentUser.requireUserId(authentication)`.
- No `ApiResponse<T>` wrapper — `ResponseEntity<T>` returned directly.
- All timestamps are `java.time.Instant`; "day"/"week" boundaries are UTC calendar days.
- Every nullable stat/delta is `Double` (boxed), and must serialize as JSON `null` — never
  `NaN`/`Infinity` — when its baseline can't support the computation.
- Tests run under `@ActiveProfiles("test")` (H2). Any `@SpringBootTest` needs
  `@MockitoBean JavaMailSender mailSender`.
- Every `./mvnw test` run needs: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test`
- Format with `./mvnw spotless:apply` before every commit (pre-commit hook enforces this).
- Windows (all UTC, computed from a `now: Instant` parameter, never a hidden `Instant.now()` call
  inside the service):
  - Day pair (executions today, failed runs): today `[todayStart, tomorrowStart)` vs yesterday
    `[yesterdayStart, todayStart)`.
  - Week pair (success rate, median run time): last 7 days `[today-6d start, tomorrowStart)` vs
    the preceding 7 days `[today-13d start, today-6d start)`.
- Success rate and median run time are computed over completed runs (`SUCCESS`+`FAILED`) only;
  `PENDING`/`RUNNING` runs are excluded from both.
- Git safety: the working tree may contain unrelated uncommitted changes from other work. Every
  commit in this plan must stage and commit ONLY the exact files its own step names, via explicit
  `git add <path>` / `git commit <path> -m "..."` — never `git add -A`, `git add .`, or a bare
  `git commit` with no pathspec. Run `git status` before and after each commit to confirm nothing
  else was swept in.

---

### Task 1: `WorkflowRunRepository` bounded-range query method

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepositoryFindBetweenTest.java`

**Interfaces:**
- Produces: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(Long userId, Instant from, Instant to)` returning `List<WorkflowRun>`. Task 3 (`DashboardService.summary`) calls this four times, once per window.

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunRepositoryFindBetweenTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void findsOnlyTheGivenUsersRunsWithinTheHalfOpenWindow() {
    User owner = saveUser("between-owner@example.com");
    User other = saveUser("between-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);

    Instant from = Instant.parse("2026-08-01T00:00:00Z");
    Instant to = Instant.parse("2026-08-02T00:00:00Z");
    saveRun(ownerWorkflow, from); // at lower boundary: included
    saveRun(ownerWorkflow, from.plusSeconds(43200)); // mid-window: included
    saveRun(ownerWorkflow, to.minusSeconds(1)); // just under upper boundary: included
    saveRun(ownerWorkflow, from.minusSeconds(1)); // before window: excluded
    saveRun(ownerWorkflow, to); // at upper boundary (exclusive): excluded
    saveRun(otherWorkflow, from.plusSeconds(3600)); // other user, in-window: excluded
    workflowRunRepository.save(
        WorkflowRun.builder().workflow(ownerWorkflow).status(WorkflowRunStatus.PENDING).build());

    var found =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            owner.getId(), from, to);

    assertThat(found).hasSize(3);
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

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowRunRepositoryFindBetweenTest`
Expected: compile error — the method doesn't exist yet.

- [ ] **Step 3: Add the derived query method**

In `src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java`, add inside
the interface (alongside the existing derived methods; `Instant` is already imported):

```java
  /**
   * A user's runs that started within the half-open window {@code [from, to)} — for the
   * dashboard's fixed-window KPI summary (today/yesterday, this-week/last-week).
   */
  List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
      Long userId, Instant from, Instant to);
```

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=WorkflowRunRepositoryFindBetweenTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java src/test/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepositoryFindBetweenTest.java
git commit src/main/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepository.java src/test/java/com/flowmatic/auth/workflow/repository/WorkflowRunRepositoryFindBetweenTest.java -m "feat(workflow): add WorkflowRunRepository bounded-range query for dashboard summary windows"
```

---

### Task 2: `SummaryStatsDTO` record

**Files:**
- Create: `src/main/java/com/flowmatic/auth/workflow/dashboard/dto/SummaryStatsDTO.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record SummaryStatsDTO(long executionsToday, Double executionsTodayDeltaPct, Double successRatePct, Double successRateDeltaPp, long failedRuns, Double failedRunsDeltaPct, Double medianRunTimeSeconds, Double medianRunTimeDeltaPct)`. Task 3 (`DashboardService.summary`) constructs these; Task 4 (`DashboardController`) returns one.

- [ ] **Step 1: Create the DTO**

```java
package com.flowmatic.auth.workflow.dashboard.dto;

/**
 * The dashboard's 4 KPI cards. Every {@code Double} field is nullable — {@code null} means
 * "undefined" (e.g. no completed runs to compute a rate/median from, or no baseline period to
 * compare against), never {@code NaN}/{@code Infinity}.
 */
public record SummaryStatsDTO(
    long executionsToday,
    Double executionsTodayDeltaPct,
    Double successRatePct,
    Double successRateDeltaPp,
    long failedRuns,
    Double failedRunsDeltaPct,
    Double medianRunTimeSeconds,
    Double medianRunTimeDeltaPct) {}
```

- [ ] **Step 2: Compile check**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/dto/SummaryStatsDTO.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/dto/SummaryStatsDTO.java -m "feat(dashboard): add SummaryStatsDTO"
```

---

### Task 3: `DashboardService.summary` — window fetching, rate/median/delta computation

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceSummaryTest.java`

**Interfaces:**
- Consumes: `WorkflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(Long, Instant, Instant)` (Task 1), `SummaryStatsDTO` (Task 2), `WorkflowRunStatus{PENDING,RUNNING,SUCCESS,FAILED}` (existing).
- Produces: `DashboardService.summary(Long userId, Instant now)` returning `SummaryStatsDTO`. Task 4 (`DashboardController`) calls this with `Instant.now()`. This is a new method added to the existing `DashboardService` class (which already has `executionsOverTime` — do not remove or modify that method).

The existing `DashboardService.java` (before this task) is:

```java
package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
   * Zero-filled per-day execution counts for {@code userId}'s workflows, oldest day first, over the
   * {@code days}-day window ending on {@code now}'s UTC calendar day (inclusive).
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

- [ ] **Step 1: Write the failing unit tests**

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
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
class DashboardServiceSummaryTest {

  @Mock WorkflowRunRepository workflowRunRepository;

  private DashboardService service;

  @BeforeEach
  void setUp() {
    service = new DashboardService(workflowRunRepository);
  }

  // "Now" is midday UTC on 2026-08-08. Window boundaries this implies (all UTC midnight):
  //   today       = [2026-08-08T00:00:00Z, 2026-08-09T00:00:00Z)
  //   yesterday   = [2026-08-07T00:00:00Z, 2026-08-08T00:00:00Z)
  //   this week   = [2026-08-02T00:00:00Z, 2026-08-09T00:00:00Z)   (today-6d .. today)
  //   last week   = [2026-07-26T00:00:00Z, 2026-08-02T00:00:00Z)   (today-13d .. today-7d)
  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
  private static final Instant TODAY_START = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant TOMORROW_START = Instant.parse("2026-08-09T00:00:00Z");
  private static final Instant YESTERDAY_START = Instant.parse("2026-08-07T00:00:00Z");
  private static final Instant WEEK_START = Instant.parse("2026-08-02T00:00:00Z");
  private static final Instant PREV_WEEK_START = Instant.parse("2026-07-26T00:00:00Z");

  private void stubWindows(
      List<WorkflowRun> today, List<WorkflowRun> yesterday, List<WorkflowRun> week, List<WorkflowRun> prevWeek) {
    given(workflowRunRepository, TODAY_START, TOMORROW_START, today);
    given(workflowRunRepository, YESTERDAY_START, TODAY_START, yesterday);
    given(workflowRunRepository, WEEK_START, TOMORROW_START, week);
    given(workflowRunRepository, PREV_WEEK_START, WEEK_START, prevWeek);
  }

  private static void given(
      WorkflowRunRepository repo, Instant from, Instant to, List<WorkflowRun> result) {
    org.mockito.Mockito.when(
            repo.findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(1L, from, to))
        .thenReturn(result);
  }

  private static WorkflowRun run(WorkflowRunStatus status) {
    return WorkflowRun.builder().workflow(Workflow.builder().build()).status(status).build();
  }

  private static WorkflowRun completedRun(WorkflowRunStatus status, Instant startedAt, double seconds) {
    return WorkflowRun.builder()
        .workflow(Workflow.builder().build())
        .status(status)
        .startedAt(startedAt)
        .completedAt(startedAt.plusMillis((long) (seconds * 1000)))
        .build();
  }

  @Test
  void zeroDataYieldsZerosAndNulls() {
    stubWindows(List.of(), List.of(), List.of(), List.of());

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats)
        .isEqualTo(new SummaryStatsDTO(0, null, null, null, 0, null, null, null));
  }

  @Test
  void executionsTodayDeltaPctComputedAgainstYesterday() {
    List<WorkflowRun> today = List.of(run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.SUCCESS));
    List<WorkflowRun> yesterday = List.of(run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.SUCCESS));
    stubWindows(today, yesterday, List.of(), List.of());

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.executionsToday()).isEqualTo(3);
    assertThat(stats.executionsTodayDeltaPct()).isEqualTo(50.0);
    assertThat(stats.failedRuns()).isEqualTo(0);
    assertThat(stats.failedRunsDeltaPct()).isNull(); // yesterday's failed count is 0 -- null baseline
  }

  @Test
  void successRatePctAndDeltaPpComputedOverWeekWindows() {
    List<WorkflowRun> week =
        List.of(
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.FAILED));
    List<WorkflowRun> prevWeek = List.of(run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.FAILED));
    stubWindows(List.of(), List.of(), week, prevWeek);

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.successRatePct()).isEqualTo(75.0);
    assertThat(stats.successRateDeltaPp()).isEqualTo(25.0);
  }

  @Test
  void medianRunTimeSecondsAndDeltaPctComputedOverWeekWindows_excludingPending() {
    List<WorkflowRun> week =
        List.of(
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-08-05T00:00:00Z"), 2.0),
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-08-05T01:00:00Z"), 4.0),
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-08-05T02:00:00Z"), 6.0),
            run(WorkflowRunStatus.PENDING)); // no startedAt/completedAt -- must not be included or NPE
    List<WorkflowRun> prevWeek =
        List.of(
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-07-28T00:00:00Z"), 1.0),
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-07-28T01:00:00Z"), 3.0));
    stubWindows(List.of(), List.of(), week, prevWeek);

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.medianRunTimeSeconds()).isEqualTo(4.0);
    assertThat(stats.medianRunTimeDeltaPct()).isEqualTo(100.0);
  }

  @Test
  void deltaIsNullWhenPreviousWeekHasNoCompletedRuns() {
    List<WorkflowRun> week =
        List.of(
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-08-06T00:00:00Z"), 5.0),
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-08-06T01:00:00Z"), 5.0));
    stubWindows(List.of(), List.of(), week, List.of());

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.successRatePct()).isEqualTo(100.0);
    assertThat(stats.successRateDeltaPp()).isNull();
    assertThat(stats.medianRunTimeSeconds()).isEqualTo(5.0);
    assertThat(stats.medianRunTimeDeltaPct()).isNull();
  }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceSummaryTest`
Expected: compile error — `SummaryStatsDTO` exists (Task 2) but `DashboardService.summary(...)` doesn't yet.

- [ ] **Step 3: Add the `summary` method and its private helpers to `DashboardService`**

Add these imports to the existing import block (`java.time.Duration` and the two new types; do not
remove any existing import):

```java
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.time.Duration;
```

Add this method and these five private helpers inside the `DashboardService` class, after the
existing `executionsOverTime` method (do not modify `executionsOverTime` itself):

```java
  /**
   * Fixed-window KPI summary for the dashboard's 4 stat cards: executions today / failed runs
   * (today vs yesterday), success rate / median run time (last 7 days vs the preceding 7 days).
   * Every {@code Double} field is {@code null}, not {@code NaN}, when its window has no data to
   * support the computation.
   */
  public SummaryStatsDTO summary(Long userId, Instant now) {
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant tomorrowStart = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant yesterdayStart = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant weekStart = today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant prevWeekStart = today.minusDays(13).atStartOfDay(ZoneOffset.UTC).toInstant();

    List<WorkflowRun> todayRuns = fetchWindow(userId, todayStart, tomorrowStart);
    List<WorkflowRun> yesterdayRuns = fetchWindow(userId, yesterdayStart, todayStart);
    List<WorkflowRun> weekRuns = fetchWindow(userId, weekStart, tomorrowStart);
    List<WorkflowRun> prevWeekRuns = fetchWindow(userId, prevWeekStart, weekStart);

    long executionsToday = todayRuns.size();
    long failedToday = countByStatus(todayRuns, WorkflowRunStatus.FAILED);

    Double successRatePct = successRate(weekRuns);
    Double successRatePrevWeek = successRate(prevWeekRuns);
    Double medianRunTimeSeconds = medianDurationSeconds(weekRuns);
    Double medianPrevWeek = medianDurationSeconds(prevWeekRuns);

    return new SummaryStatsDTO(
        executionsToday,
        pctDelta(executionsToday, yesterdayRuns.size()),
        successRatePct,
        ppDelta(successRatePct, successRatePrevWeek),
        failedToday,
        pctDelta(failedToday, countByStatus(yesterdayRuns, WorkflowRunStatus.FAILED)),
        medianRunTimeSeconds,
        pctDelta(medianRunTimeSeconds, medianPrevWeek));
  }

  private List<WorkflowRun> fetchWindow(Long userId, Instant from, Instant to) {
    return workflowRunRepository
        .findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(userId, from, to);
  }

  private static long countByStatus(List<WorkflowRun> runs, WorkflowRunStatus status) {
    return runs.stream().filter(r -> r.getStatus() == status).count();
  }

  private static Double successRate(List<WorkflowRun> runs) {
    long success = countByStatus(runs, WorkflowRunStatus.SUCCESS);
    long failed = countByStatus(runs, WorkflowRunStatus.FAILED);
    long completed = success + failed;
    return completed == 0 ? null : 100.0 * success / completed;
  }

  private static Double medianDurationSeconds(List<WorkflowRun> runs) {
    List<Double> durations =
        runs.stream()
            .filter(
                r ->
                    (r.getStatus() == WorkflowRunStatus.SUCCESS
                            || r.getStatus() == WorkflowRunStatus.FAILED)
                        && r.getCompletedAt() != null)
            .map(r -> Duration.between(r.getStartedAt(), r.getCompletedAt()).toMillis() / 1000.0)
            .sorted()
            .toList();
    if (durations.isEmpty()) {
      return null;
    }
    int n = durations.size();
    return n % 2 == 1
        ? durations.get(n / 2)
        : (durations.get(n / 2 - 1) + durations.get(n / 2)) / 2.0;
  }

  private static Double pctDelta(long current, long previous) {
    return previous == 0 ? null : 100.0 * (current - previous) / previous;
  }

  private static Double pctDelta(Double current, Double previous) {
    if (current == null || previous == null || previous == 0.0) {
      return null;
    }
    return 100.0 * (current - previous) / previous;
  }

  private static Double ppDelta(Double current, Double previous) {
    return (current == null || previous == null) ? null : current - previous;
  }
```

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceSummaryTest`
Expected: PASS (5 tests). Also re-run `DashboardServiceTest` (the existing `executionsOverTime`
test class) to confirm it still passes unmodified:
`JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardServiceTest`

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceSummaryTest.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardService.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardServiceSummaryTest.java -m "feat(dashboard): add DashboardService.summary with window-based rate/median/delta computation"
```

---

### Task 4: `DashboardController` — `/api/dashboard/summary` endpoint

**Files:**
- Modify: `src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java`
- Test: `src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerSummaryIntegrationTest.java`

**Interfaces:**
- Consumes: `DashboardService.summary(Long, Instant)` (Task 3), `CurrentUser.requireUserId(Authentication)` (existing).
- Produces: `GET /api/dashboard/summary` → `200 OK` with a `SummaryStatsDTO` body. Nothing later in this plan consumes this — it's the final task. This adds a second `@GetMapping` to the existing `DashboardController` class (which already has `/executions-over-time`) — do not modify the existing endpoint.

The existing `DashboardController.java` (before this task) is:

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

- [ ] **Step 1: Write the failing integration test**

Auth follows this codebase's established convention: `@WithMockUser(username = <email>)` fakes the
`Authentication` — it does not create a DB row, so a matching `User` row must be seeded first (see
`DashboardControllerIntegrationTest` for the same pattern already used in this package). Use a
self-managed `ObjectMapper` (`new ObjectMapper()`), not `@Autowired` — this stack's framework bean
is Jackson 3, incompatible with the Jackson 2 `ObjectMapper` type these tests deserialize with
(established convention; see `DashboardControllerIntegrationTest`, `WorkflowRunQuotaIntegrationTest`, etc.).

```java
package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
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
class DashboardControllerSummaryIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "summary-empty@example.com")
  void returnsZerosAndNullsForNewUserWithNoRuns() throws Exception {
    saveUser("summary-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.executionsToday()).isEqualTo(0);
    assertThat(stats.executionsTodayDeltaPct()).isNull();
    assertThat(stats.successRatePct()).isNull();
    assertThat(stats.successRateDeltaPp()).isNull();
    assertThat(stats.failedRuns()).isEqualTo(0);
    assertThat(stats.failedRunsDeltaPct()).isNull();
    assertThat(stats.medianRunTimeSeconds()).isNull();
    assertThat(stats.medianRunTimeDeltaPct()).isNull();
  }

  @Test
  @WithMockUser(username = "summary-owner@example.com")
  void countsOnlyTheCallersOwnRunsToday() throws Exception {
    User owner = saveUser("summary-owner@example.com");
    User other = saveUser("summary-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(otherWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.executionsToday()).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "summary-rate@example.com")
  void computesSuccessRateOverTheCallersRunsThisWeek() throws Exception {
    User owner = saveUser("summary-rate@example.com");
    Workflow workflow = saveWorkflow(owner);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.FAILED, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.successRatePct()).isEqualTo(75.0);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Summary User")
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

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerSummaryIntegrationTest`
Expected: compile error — there is no `/summary` mapping yet.

- [ ] **Step 3: Add the `/summary` endpoint to `DashboardController`**

Add this method inside the existing `DashboardController` class, after the existing
`executionsOverTime` method (do not modify that method):

```java
  @GetMapping("/summary")
  public ResponseEntity<SummaryStatsDTO> summary(Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(dashboardService.summary(userId, Instant.now()));
  }
```

Add this import to the existing import block:

```java
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
```

- [ ] **Step 4: Run the tests again to confirm they pass**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test -Dtest=DashboardControllerSummaryIntegrationTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `JWT_SECRET=$(openssl rand -hex 32) GROQ_API_KEY=test-key MAIL_USERNAME=test MAIL_PASSWORD=test ./mvnw test`
Expected: BUILD SUCCESS, all tests pass (existing + the ones added in this plan)

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerSummaryIntegrationTest.java
git commit src/main/java/com/flowmatic/auth/workflow/dashboard/DashboardController.java src/test/java/com/flowmatic/auth/workflow/dashboard/DashboardControllerSummaryIntegrationTest.java -m "feat(dashboard): add GET /api/dashboard/summary"
```
