package com.flowmatic.auth.workflow.dashboard;

import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Aggregates {@link WorkflowRun} rows for the dashboard: per-UTC-day counts for the
 * executions-over-time chart ({@link #executionsOverTime}), and fixed-window KPI stats for the
 * summary cards ({@link #summary}).
 */
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
    // Clipped to the same elapsed-today offset so "today so far" is compared against the matching
    // elapsed portion of yesterday, not the whole day -- otherwise the delta is systematically
    // biased negative for as long as today remains partially elapsed (which is always, until
    // 24:00).
    Instant yesterdayElapsedEnd = yesterdayStart.plus(Duration.between(todayStart, now));
    Instant weekStart = today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant prevWeekStart = today.minusDays(13).atStartOfDay(ZoneOffset.UTC).toInstant();

    List<WorkflowRun> todayRuns = fetchWindow(userId, todayStart, tomorrowStart);
    List<WorkflowRun> yesterdayRuns = fetchWindow(userId, yesterdayStart, yesterdayElapsedEnd);
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

  /**
   * All 4 {@link WorkflowRunStatus} counts over the last 30 days, always exactly 4 entries in enum
   * declaration order — for the dashboard's executions-by-status pie chart.
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
}
