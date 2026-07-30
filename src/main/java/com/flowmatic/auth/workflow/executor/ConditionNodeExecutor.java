package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
import com.flowmatic.auth.workflow.expression.ExpressionEvaluator;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates a boolean {@code expr} against the namespaced context and outputs {@code {result:
 * bool}}. The engine uses that result to choose which outgoing branch (edge {@code sourceHandle}
 * true/false) is live.
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

  private final ExpressionEvaluator expressionEvaluator;

  public ConditionNodeExecutor(ExpressionEvaluator expressionEvaluator) {
    this.expressionEvaluator = expressionEvaluator;
  }

  @Override
  public NodeType supports() {
    return NodeType.CONDITION;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    Object exprCfg = context.configValue("expr");
    if (exprCfg == null || exprCfg.toString().isBlank()) {
      return NodeExecutionResult.failure("CONDITION node requires config 'expr'");
    }
    try {
      boolean result = expressionEvaluator.evaluate(exprCfg.toString(), context.getContext());
      return NodeExecutionResult.success(Map.of("result", result));
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("CONDITION expression error: " + e.getMessage());
    }
  }
}
