package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.workflow.entity.NodeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps each {@link NodeType} to its single {@link NodeExecutor}. */
@Component
public class NodeExecutorRegistry {

  private final Map<NodeType, NodeExecutor> byType = new EnumMap<>(NodeType.class);

  public NodeExecutorRegistry(List<NodeExecutor> executors) {
    for (NodeExecutor executor : executors) {
      NodeExecutor existing = byType.put(executor.supports(), executor);
      if (existing != null) {
        throw new IllegalStateException(
            "Two executors registered for node type " + executor.supports());
      }
    }
  }

  public NodeExecutor get(NodeType type) {
    NodeExecutor executor = byType.get(type);
    if (executor == null) {
      throw new IllegalStateException("No executor registered for node type " + type);
    }
    return executor;
  }
}
