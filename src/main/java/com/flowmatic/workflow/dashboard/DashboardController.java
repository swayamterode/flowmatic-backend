package com.flowmatic.workflow.dashboard;

import com.flowmatic.common.web.CurrentUser;
import com.flowmatic.workflow.dashboard.dto.ExecutionRowDTO;
import com.flowmatic.workflow.dashboard.dto.StatusBreakdownDTO;
import com.flowmatic.workflow.dashboard.dto.SummaryStatsDTO;
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

/** Backs the dashboard's executions-over-time chart and its KPI summary cards. */
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

  @GetMapping("/summary")
  public ResponseEntity<SummaryStatsDTO> summary(Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(dashboardService.summary(userId, Instant.now()));
  }

  @GetMapping("/executions-by-status")
  public ResponseEntity<List<StatusBreakdownDTO>> executionsByStatus(
      Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    return ResponseEntity.ok(dashboardService.executionsByStatus(userId, Instant.now()));
  }
}
