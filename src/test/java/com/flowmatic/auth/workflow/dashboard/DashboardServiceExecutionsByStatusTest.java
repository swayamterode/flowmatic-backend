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
