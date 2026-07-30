package com.flowmatic.auth.workflow.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.entity.NodeRunLog;
import com.flowmatic.auth.workflow.entity.NodeRunStatus;
import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.execution.WorkflowGraph.GraphEdge;
import com.flowmatic.auth.workflow.execution.WorkflowGraph.GraphNode;
import com.flowmatic.auth.workflow.repository.NodeRunLogRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Queues and runs workflows. A run request only <em>enqueues</em> a PENDING {@link WorkflowRun}
 * ({@link #enqueue}); the {@code WorkflowRunScheduler} then drains the queue one run at a time via
 * {@link #runNextPending()}, so runs execute strictly sequentially (FIFO by creation order).
 *
 * <p>{@link #execute} topologically sorts the graph, threads each node's output into a namespaced
 * context (keyed by node id), dispatches each node to its {@link NodeExecutor}, and persists a
 * {@link NodeRunLog} row per node as it happens. Branching: after a CONDITION node runs, only edges
 * whose {@code sourceHandle} matches its boolean result stay active; nodes reachable only through
 * inactive edges are marked SKIPPED.
 */
@Service
public class WorkflowExecutionService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);
  private static final int ERROR_MESSAGE_MAX = 2048;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkflowRepository workflowRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final NodeRunLogRepository nodeRunLogRepository;
  private final NodeExecutorRegistry executorRegistry;

  public WorkflowExecutionService(
      WorkflowRepository workflowRepository,
      WorkflowRunRepository workflowRunRepository,
      NodeRunLogRepository nodeRunLogRepository,
      NodeExecutorRegistry executorRegistry) {
    this.workflowRepository = workflowRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.nodeRunLogRepository = nodeRunLogRepository;
    this.executorRegistry = executorRegistry;
  }

  /**
   * Deletes a workflow together with its run history. The {@code workflow_runs} and {@code
   * node_run_logs} foreign keys are RESTRICT, so children must go first — deepest table first.
   *
   * @throws ResponseStatusException 409 if a run is queued or executing, since the drainer would
   *     otherwise be writing node logs for rows this transaction is removing.
   */
  @Transactional
  public void deleteWithHistory(Long workflowId) {
    if (workflowRunRepository.existsByWorkflow_IdAndStatusIn(
        workflowId, List.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Workflow has a run in progress; try again once it finishes");
    }
    nodeRunLogRepository.deleteByWorkflowId(workflowId);
    workflowRunRepository.deleteByWorkflowId(workflowId);
    workflowRepository.deleteById(workflowId);
  }

  /** Enqueues a run: persists a PENDING {@link WorkflowRun} and returns immediately. */
  public WorkflowRun enqueue(Long workflowId) {
    Workflow workflow =
        workflowRepository
            .findById(workflowId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
    return workflowRunRepository.save(
        WorkflowRun.builder().workflow(workflow).status(WorkflowRunStatus.PENDING).build());
  }

  /**
   * Executes the oldest queued run, if any. Returns true if a run was processed. Intended to be
   * called by a single-threaded scheduler so at most one run executes at a time.
   */
  public boolean runNextPending() {
    Optional<WorkflowRun> next =
        workflowRunRepository.findFirstByStatusOrderByIdAsc(WorkflowRunStatus.PENDING);
    if (next.isEmpty()) {
      return false;
    }
    execute(next.get());
    return true;
  }

  /** Runs a single (already-persisted) run to completion. */
  public WorkflowRun execute(WorkflowRun runEntity) {
    runEntity.setStatus(WorkflowRunStatus.RUNNING);
    runEntity.setStartedAt(Instant.now());
    runEntity = workflowRunRepository.save(runEntity);

    boolean failed = false;
    try {
      Workflow workflow =
          workflowRepository
              .findById(runEntity.getWorkflow().getId())
              .orElseThrow(() -> new IllegalStateException("Workflow disappeared mid-run"));
      Long userId = workflow.getUser().getId();

      WorkflowGraph graph = parseGraph(workflow);
      List<GraphNode> ordered = TopologicalSorter.sort(graph);
      Map<String, GraphNode> nodesById = new LinkedHashMap<>();
      ordered.forEach(n -> nodesById.put(n.id(), n));
      Map<String, List<GraphEdge>> incoming = incomingEdges(graph);

      Map<String, Object> context = new LinkedHashMap<>();
      Map<String, NodeRunStatus> statusByNode = new LinkedHashMap<>();

      for (GraphNode node : ordered) {
        List<GraphEdge> incom = incoming.getOrDefault(node.id(), List.of());
        List<GraphEdge> activeIn =
            incom.stream().filter(e -> edgeActive(e, context, statusByNode, nodesById)).toList();

        if (!incom.isEmpty() && activeIn.isEmpty()) {
          persistSkipped(runEntity, node);
          statusByNode.put(node.id(), NodeRunStatus.SKIPPED);
          continue;
        }

        NodeRunLog nodeLog =
            nodeRunLogRepository.save(
                NodeRunLog.builder()
                    .workflowRun(runEntity)
                    .nodeId(node.id())
                    .nodeType(node.type())
                    .status(NodeRunStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build());

        NodeExecutionContext ctx =
            NodeExecutionContext.builder()
                .nodeId(node.id())
                .nodeType(node.type())
                .config(node.data())
                .input(mergeInputs(activeIn, context))
                .context(context)
                .userId(userId)
                .build();

        NodeExecutionResult result;
        try {
          result = executorRegistry.get(node.type()).execute(ctx);
        } catch (RuntimeException e) {
          log.error("Node {} ({}) threw", node.id(), node.type(), e);
          result = NodeExecutionResult.failure("Unhandled error: " + e.getMessage());
        }

        nodeLog.setCompletedAt(Instant.now());
        if (result.isSuccess()) {
          context.put(node.id(), result.getOutput());
          statusByNode.put(node.id(), NodeRunStatus.SUCCESS);
          nodeLog.setStatus(NodeRunStatus.SUCCESS);
          nodeLog.setOutputJson(toJson(result.getOutput()));
          nodeRunLogRepository.save(nodeLog);
        } else {
          statusByNode.put(node.id(), NodeRunStatus.FAILED);
          nodeLog.setStatus(NodeRunStatus.FAILED);
          nodeLog.setErrorMessage(composeError(result));
          nodeRunLogRepository.save(nodeLog);
          failed = true;
          break;
        }
      }
    } catch (RuntimeException e) {
      log.error("Run {} failed to execute", runEntity.getId(), e);
      failed = true;
    }

    runEntity.setStatus(failed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
    runEntity.setCompletedAt(Instant.now());
    return workflowRunRepository.save(runEntity);
  }

  /** An edge is active iff its source succeeded and (no handle, or CONDITION result matches). */
  private static boolean edgeActive(
      GraphEdge edge,
      Map<String, Object> context,
      Map<String, NodeRunStatus> statusByNode,
      Map<String, GraphNode> nodesById) {
    if (statusByNode.get(edge.source()) != NodeRunStatus.SUCCESS) {
      return false;
    }
    String handle = edge.sourceHandle();
    if (handle == null || handle.isBlank()) {
      return true;
    }
    GraphNode source = nodesById.get(edge.source());
    if (source == null || source.type() != NodeType.CONDITION) {
      return true; // a handle on a non-condition edge is ignored
    }
    boolean result =
        context.get(edge.source()) instanceof Map<?, ?> out
            && Boolean.TRUE.equals(out.get("result"));
    if (handle.equalsIgnoreCase("true")) {
      return result;
    }
    if (handle.equalsIgnoreCase("false")) {
      return !result;
    }
    return true;
  }

  private void persistSkipped(WorkflowRun runEntity, GraphNode node) {
    Instant now = Instant.now();
    nodeRunLogRepository.save(
        NodeRunLog.builder()
            .workflowRun(runEntity)
            .nodeId(node.id())
            .nodeType(node.type())
            .status(NodeRunStatus.SKIPPED)
            .startedAt(now)
            .completedAt(now)
            .build());
  }

  private WorkflowGraph parseGraph(Workflow workflow) {
    try {
      return MAPPER.readValue(workflow.getGraphJson(), WorkflowGraph.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Workflow " + workflow.getId() + " has invalid graph_json: " + e.getMessage(), e);
    }
  }

  private static Map<String, List<GraphEdge>> incomingEdges(WorkflowGraph graph) {
    Map<String, List<GraphEdge>> incoming = new LinkedHashMap<>();
    for (GraphEdge edge : graph.edges()) {
      incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
    }
    return incoming;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mergeInputs(
      List<GraphEdge> activeIn, Map<String, Object> context) {
    Map<String, Object> merged = new LinkedHashMap<>();
    for (GraphEdge edge : activeIn) {
      Object out = context.get(edge.source());
      if (out instanceof Map<?, ?> map) {
        merged.putAll((Map<String, Object>) map);
      }
    }
    return merged;
  }

  private String toJson(Map<String, Object> output) {
    try {
      return MAPPER.writeValueAsString(output);
    } catch (Exception e) {
      return "{\"_serializationError\":\"" + e.getMessage() + "\"}";
    }
  }

  private static String composeError(NodeExecutionResult result) {
    StringBuilder sb = new StringBuilder();
    if (result.getErrorMessage() != null) {
      sb.append(result.getErrorMessage());
    }
    if (result.getRawDetail() != null) {
      sb.append("\n--- raw model response ---\n").append(result.getRawDetail());
    }
    String msg = sb.toString();
    return msg.length() > ERROR_MESSAGE_MAX ? msg.substring(0, ERROR_MESSAGE_MAX) : msg;
  }
}
