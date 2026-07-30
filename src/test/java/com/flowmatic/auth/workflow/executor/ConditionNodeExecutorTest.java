package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.ExpressionEvaluator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionNodeExecutorTest {

  private final ConditionNodeExecutor executor =
      new ConditionNodeExecutor(new ExpressionEvaluator());

  private NodeExecutionResult evaluate(String expr) {
    return executor.execute(
        NodeExecutionContext.builder()
            .nodeId("c")
            .nodeType(NodeType.CONDITION)
            .context(Map.of("ai", Map.of("priority", "high", "count", 3)))
            .config(Map.of("expr", expr))
            .build());
  }

  @Test
  void trueBranch() {
    NodeExecutionResult r = evaluate("ai.priority == 'high'");
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getOutput()).containsEntry("result", true);
  }

  @Test
  void falseBranch() {
    NodeExecutionResult r = evaluate("ai.count > 10");
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getOutput()).containsEntry("result", false);
  }

  @Test
  void missingExprFails() {
    NodeExecutionResult r =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("c")
                .nodeType(NodeType.CONDITION)
                .config(Map.of())
                .build());
    assertThat(r.isSuccess()).isFalse();
  }
}
