package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.workflow.execution.WorkflowGraph.GraphEdge;
import com.flowmatic.auth.workflow.execution.WorkflowGraph.GraphNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kahn's-algorithm topological sort of a workflow graph. Fails fast on cycles. */
public final class TopologicalSorter {

  private TopologicalSorter() {}

  public static List<GraphNode> sort(WorkflowGraph graph) {
    Map<String, GraphNode> nodesById = new LinkedHashMap<>();
    for (GraphNode node : graph.nodes()) {
      if (node.id() == null) {
        throw new IllegalArgumentException("Graph contains a node with no id");
      }
      nodesById.put(node.id(), node);
    }

    Map<String, Integer> indegree = new HashMap<>();
    Map<String, List<String>> adjacency = new HashMap<>();
    for (String id : nodesById.keySet()) {
      indegree.put(id, 0);
      adjacency.put(id, new ArrayList<>());
    }
    for (GraphEdge edge : graph.edges()) {
      if (!nodesById.containsKey(edge.source()) || !nodesById.containsKey(edge.target())) {
        throw new IllegalArgumentException(
            "Edge references unknown node: " + edge.source() + " -> " + edge.target());
      }
      adjacency.get(edge.source()).add(edge.target());
      indegree.merge(edge.target(), 1, Integer::sum);
    }

    // Seed the queue in declaration order so independent nodes run deterministically.
    Deque<String> ready = new ArrayDeque<>();
    for (String id : nodesById.keySet()) {
      if (indegree.get(id) == 0) {
        ready.add(id);
      }
    }

    List<GraphNode> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.poll();
      ordered.add(nodesById.get(id));
      for (String next : adjacency.get(id)) {
        if (indegree.merge(next, -1, Integer::sum) == 0) {
          ready.add(next);
        }
      }
    }

    if (ordered.size() != nodesById.size()) {
      throw new IllegalArgumentException("Workflow graph has a cycle");
    }
    return ordered;
  }
}
