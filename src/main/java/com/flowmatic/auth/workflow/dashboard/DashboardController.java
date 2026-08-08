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
