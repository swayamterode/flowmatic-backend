package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
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
class DashboardServiceTest {

  @Mock WorkflowRunRepository workflowRunRepository;

  // Constructed in @BeforeEach, not as a field initializer: MockitoExtension injects @Mock
  // fields via a TestInstancePostProcessor, which runs after field initializers, so building
  // `service` inline here would capture workflowRunRepository while it's still null.
  private DashboardService service;

  @BeforeEach
  void setUp() {
    service = new DashboardService(workflowRunRepository);
  }

  // "Now" is midday UTC on 2026-08-08, so "today" is unambiguously 2026-08-08 regardless of the
  // exact hour — avoids the test being sensitive to a boundary-of-day edge case it isn't testing.
  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

  @Test
  void zeroFillsEveryDayWhenNoRunsExist() {
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(
            eq(1L), anyInstant()))
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
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(
            eq(1L), anyInstant()))
        .thenReturn(List.of(a, b, c));

    List<ExecutionRowDTO> rows = service.executionsOverTime(1L, 7, NOW);

    ExecutionRowDTO aug8 =
        rows.stream().filter(r -> r.date().equals("2026-08-08")).findFirst().get();
    ExecutionRowDTO aug7 =
        rows.stream().filter(r -> r.date().equals("2026-08-07")).findFirst().get();
    assertThat(aug8.executions()).isEqualTo(2);
    assertThat(aug7.executions()).isEqualTo(1);
  }

  @Test
  void returnsSixtyEntriesFor60DayWindow() {
    when(workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(
            eq(1L), anyInstant()))
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
