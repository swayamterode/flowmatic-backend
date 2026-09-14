package com.flowmatic.nodes;

import com.flowmatic.workflow.entity.NodeType;
import com.flowmatic.nodes.NodeExecutionContext;
import com.flowmatic.nodes.NodeExecutionResult;
import com.flowmatic.nodes.NodeExecutor;
import com.flowmatic.workflow.expression.ExpressionEvaluator;
import com.flowmatic.workflow.expression.TemplateResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keeps the elements of an array for which {@code expr} is true. {@code source} defaults to the
 * upstream {@code items}/{@code rows}; the predicate is evaluated with each element as its scope
 * (so bare fields like {@code rating > 4} refer to the element). Output: {@code {items:[...]}}.
 */
@Component
public class FilterNodeExecutor implements NodeExecutor {

  private final TemplateResolver templateResolver;
  private final ExpressionEvaluator expressionEvaluator;

  public FilterNodeExecutor(
      TemplateResolver templateResolver, ExpressionEvaluator expressionEvaluator) {
    this.templateResolver = templateResolver;
    this.expressionEvaluator = expressionEvaluator;
  }

  @Override
  public NodeType supports() {
    return NodeType.FILTER;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    Object exprCfg = context.configValue("expr");
    if (exprCfg == null || exprCfg.toString().isBlank()) {
      return NodeExecutionResult.failure("FILTER node requires config 'expr'");
    }
    String expr = exprCfg.toString();

    List<?> source;
    try {
      source = ExecutorSupport.resolveArraySource(templateResolver, context);
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("FILTER source error: " + e.getMessage());
    }

    List<Object> kept = new ArrayList<>();
    try {
      for (Object element : source) {
        if (expressionEvaluator.evaluate(expr, ExecutorSupport.elementScope(element))) {
          kept.add(element);
        }
      }
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("FILTER expression error: " + e.getMessage());
    }
    return NodeExecutionResult.success(Map.of("items", kept));
  }
}
