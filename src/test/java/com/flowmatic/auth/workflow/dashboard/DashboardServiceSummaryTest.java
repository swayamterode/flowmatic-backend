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
  private static final Instant YESTERDAY_ELAPSED_END = Instant.parse("2026-08-07T12:00:00Z");
  private static final Instant WEEK_START = Instant.parse("2026-08-02T00:00:00Z");
  private static final Instant PREV_WEEK_START = Instant.parse("2026-07-26T00:00:00Z");

  private void stubWindows(
      List<WorkflowRun> today,
      List<WorkflowRun> yesterday,
      List<WorkflowRun> week,
      List<WorkflowRun> prevWeek) {
    given(workflowRunRepository, TODAY_START, TOMORROW_START, today);
    given(workflowRunRepository, YESTERDAY_START, YESTERDAY_ELAPSED_END, yesterday);
    given(workflowRunRepository, WEEK_START, TOMORROW_START, week);
    given(workflowRunRepository, PREV_WEEK_START, WEEK_START, prevWeek);
  }

  private static void given(
      WorkflowRunRepository repo, Instant from, Instant to, List<WorkflowRun> result) {
    org.mockito.Mockito.when(
            repo.findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                1L, from, to))
        .thenReturn(result);
  }

  private static WorkflowRun run(WorkflowRunStatus status) {
    return WorkflowRun.builder().workflow(Workflow.builder().build()).status(status).build();
  }

  private static WorkflowRun completedRun(
      WorkflowRunStatus status, Instant startedAt, double seconds) {
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

    assertThat(stats).isEqualTo(new SummaryStatsDTO(0, null, null, null, 0, null, null, null));
  }

  @Test
  void executionsTodayDeltaPctComputedAgainstYesterday() {
    List<WorkflowRun> today =
        List.of(
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS));
    List<WorkflowRun> yesterday =
        List.of(run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.SUCCESS));
    stubWindows(today, yesterday, List.of(), List.of());

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.executionsToday()).isEqualTo(3);
    assertThat(stats.executionsTodayDeltaPct()).isEqualTo(50.0);
    assertThat(stats.failedRuns()).isEqualTo(0);
    assertThat(stats.failedRunsDeltaPct())
        .isNull(); // yesterday's failed count is 0 -- null baseline
  }

  @Test
  void successRatePctAndDeltaPpComputedOverWeekWindows() {
    List<WorkflowRun> week =
        List.of(
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.SUCCESS),
            run(WorkflowRunStatus.FAILED));
    List<WorkflowRun> prevWeek =
        List.of(run(WorkflowRunStatus.SUCCESS), run(WorkflowRunStatus.FAILED));
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
            run(
                WorkflowRunStatus
                    .PENDING)); // no startedAt/completedAt -- must not be included or NPE
    List<WorkflowRun> prevWeek =
        List.of(
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-07-28T00:00:00Z"), 1.0),
            completedRun(WorkflowRunStatus.SUCCESS, Instant.parse("2026-07-28T01:00:00Z"), 3.0));
    stubWindows(List.of(), List.of(), week, prevWeek);

    SummaryStatsDTO stats = service.summary(1L, NOW);

    assertThat(stats.medianRunTimeSeconds()).isEqualTo(4.0);
    assertThat(stats.medianRunTimeDeltaPct()).isEqualTo(100.0);
    assertThat(stats.successRatePct())
        .isEqualTo(100.0); // PENDING excluded from both numerator and denominator
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
