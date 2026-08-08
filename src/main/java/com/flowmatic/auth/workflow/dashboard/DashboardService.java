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
