package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
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
