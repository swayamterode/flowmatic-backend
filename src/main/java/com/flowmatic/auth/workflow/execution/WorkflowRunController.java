package com.flowmatic.auth.workflow.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.entity.NodeRunLog;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.repository.NodeRunLogRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import com.flowmatic.auth.workflow.web.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Triggers workflow execution and exposes run results (the debugging surface over HTTP). */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowRunController {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkflowExecutionService executionService;
  private final WorkflowRepository workflowRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final NodeRunLogRepository nodeRunLogRepository;
  private final CurrentUser currentUser;

  public WorkflowRunController(
      WorkflowExecutionService executionService,
      WorkflowRepository workflowRepository,
      WorkflowRunRepository workflowRunRepository,
      NodeRunLogRepository nodeRunLogRepository,
      CurrentUser currentUser) {
    this.executionService = executionService;
    this.workflowRepository = workflowRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.nodeRunLogRepository = nodeRunLogRepository;
    this.currentUser = currentUser;
  }

  @PostMapping("/{id}/run")
  public ResponseEntity<?> run(@PathVariable Long id, Authentication authentication) {
    requireOwnedWorkflow(id, authentication);
    // Enqueue only; the scheduled drainer executes runs one at a time. Poll the run for progress.
    WorkflowRun run = executionService.enqueue(id);
    return ResponseEntity.accepted().body(runSummary(run));
  }

  @GetMapping("/{id}/runs")
  public ResponseEntity<?> runs(@PathVariable Long id, Authentication authentication) {
    requireOwnedWorkflow(id, authentication);
    List<Map<String, Object>> runs =
        workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(id).stream()
            .map(this::runSummary)
            .toList();
    return ResponseEntity.ok(runs);
  }

  @GetMapping("/runs/{runId}")
  public ResponseEntity<?> runDetail(@PathVariable Long runId, Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    WorkflowRun run =
        workflowRunRepository
            .findById(runId)
            .filter(r -> r.getWorkflow().getUser().getId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

    List<Map<String, Object>> nodes =
        nodeRunLogRepository.findByWorkflowRun_IdOrderByStartedAtAsc(runId).stream()
            .map(this::nodeLog)
            .toList();

    Map<String, Object> body = new LinkedHashMap<>(runSummary(run));
    body.put("nodes", nodes);
    return ResponseEntity.ok(body);
  }

  private void requireOwnedWorkflow(Long id, Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    Workflow workflow =
        workflowRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    if (!workflow.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found");
    }
  }

  private Map<String, Object> runSummary(WorkflowRun run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.getId());
    m.put("status", run.getStatus());
    m.put("startedAt", String.valueOf(run.getStartedAt()));
    m.put("completedAt", String.valueOf(run.getCompletedAt()));
    return m;
  }

  private Map<String, Object> nodeLog(NodeRunLog logRow) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nodeId", logRow.getNodeId());
    m.put("nodeType", logRow.getNodeType());
    m.put("status", logRow.getStatus());
    m.put("output", parse(logRow.getOutputJson()));
    m.put("errorMessage", logRow.getErrorMessage());
    m.put("startedAt", String.valueOf(logRow.getStartedAt()));
    m.put("completedAt", String.valueOf(logRow.getCompletedAt()));
    return m;
  }

  private static Object parse(String json) {
    if (json == null) {
      return null;
    }
    try {
      return MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      return json;
    }
  }
}
