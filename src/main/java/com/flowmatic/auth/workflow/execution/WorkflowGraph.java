package com.flowmatic.auth.workflow.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowmatic.auth.workflow.entity.NodeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Deserialized React Flow graph stored in {@code workflows.graph_json}. Unknown fields (position,
 * styling, etc.) are ignored. Expected shape:
 *
 * <pre>{@code
 * {
 *   "nodes": [ {"id": "n1", "type": "TRIGGER", "data": { ...config... }}, ... ],
 *   "edges": [ {"source": "n1", "target": "n2"}, ... ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {

  public WorkflowGraph {
    nodes = nodes == null ? List.of() : nodes;
    edges = edges == null ? List.of() : edges;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GraphNode(String id, NodeType type, Map<String, Object> data) {
    public Map<String, Object> data() {
      return data == null ? Collections.emptyMap() : data;
    }
  }

  /**
   * {@code sourceHandle} labels which branch this edge leaves a CONDITION node by ("true"/"false");
   * null for ordinary edges. Matches React Flow's edge field of the same name.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GraphEdge(String source, String target, String sourceHandle) {}
}
