package com.flowmatic.auth.workflow.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.dashboard.DashboardService;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.execution.WorkflowExecutionService;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.web.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exposes workflow/dashboard actions as MCP tools, scoped to whichever user's token is calling.
 * Thin wrappers only — same repository/service calls {@code WorkflowController} / {@code
 * WorkflowRunController} / {@code DashboardController} already make.
 */
@Component
public class WorkflowMcpTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkflowRepository workflowRepository;
  private final WorkflowExecutionService executionService;
  private final DashboardService dashboardService;
  private final CurrentUser currentUser;

  public WorkflowMcpTools(
      WorkflowRepository workflowRepository,
      WorkflowExecutionService executionService,
      DashboardService dashboardService,
      CurrentUser currentUser) {
    this.workflowRepository = workflowRepository;
    this.executionService = executionService;
    this.dashboardService = dashboardService;
    this.currentUser = currentUser;
  }

  @McpTool(
      name = "list_workflows",
      description = "List the workflows owned by the current Flowmatic user.")
  public List<Map<String, Object>> listWorkflows() {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return workflowRepository.findByUser_Id(userId).stream().map(this::summary).toList();
  }

  @McpTool(
      name = "get_workflow",
      description = "Get a single workflow's details, including its node graph, by id.")
  public Map<String, Object> getWorkflow(
      @McpToolParam(description = "The workflow id", required = true) Long workflowId) {
    return detail(requireOwned(workflowId));
  }

  @McpTool(
      name = "run_workflow",
      description =
          "Enqueue a run of the given workflow. Runs execute one at a time; check "
              + "get_dashboard_summary afterwards to see the result.")
  public Map<String, Object> runWorkflow(
      @McpToolParam(description = "The workflow id to run", required = true) Long workflowId) {
    requireOwned(workflowId);
    WorkflowRun run = executionService.enqueue(workflowId);
    return runSummary(run);
  }

  @McpTool(
      name = "get_dashboard_summary",
      description =
          "Get aggregate execution stats for the current user: executions today, success "
              + "rate, failed runs, median run time.")
  public SummaryStatsDTO getDashboardSummary() {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return dashboardService.summary(userId, Instant.now());
  }

  private static Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  private Workflow requireOwned(Long id) {
    Long userId = currentUser.requireUserId(currentAuthentication());
    return workflowRepository
        .findById(id)
        .filter(w -> w.getUser().getId().equals(userId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
  }

  private Map<String, Object> summary(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private Map<String, Object> detail(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "graph", fromJson(w.getGraphJson()),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private Map<String, Object> runSummary(WorkflowRun run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.getId());
    m.put("status", run.getStatus());
    m.put("startedAt", String.valueOf(run.getStartedAt()));
    m.put("completedAt", String.valueOf(run.getCompletedAt()));
    return m;
  }

  private static Object fromJson(String json) {
    try {
      return MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      return json;
    }
  }
}
