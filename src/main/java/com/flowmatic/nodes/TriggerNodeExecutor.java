package com.flowmatic.nodes;

import com.flowmatic.workflow.entity.NodeType;
import com.flowmatic.nodes.NodeExecutionContext;
import com.flowmatic.nodes.NodeExecutionResult;
import com.flowmatic.nodes.NodeExecutor;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Entry point of a workflow. Emits the configured {@code payload} object (so downstream nodes can
 * read {@code {{trigger.*}}}), or {@code {triggered:true}} if none is set.
 */
@Component
public class TriggerNodeExecutor implements NodeExecutor {

  @Override
  public NodeType supports() {
    return NodeType.TRIGGER;
  }

  @Override
  @SuppressWarnings("unchecked")
  public NodeExecutionResult execute(NodeExecutionContext context) {
    Object payload = context.configValue("payload");
    if (payload instanceof Map<?, ?> map) {
      return NodeExecutionResult.success((Map<String, Object>) map);
    }
    return NodeExecutionResult.success(Map.of("triggered", true));
  }
}
