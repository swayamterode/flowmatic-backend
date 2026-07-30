package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;

/** Small shared helpers for the array-processing executors (FILTER / TRANSFORM). */
final class ExecutorSupport {

  private ExecutorSupport() {}

  /**
   * Resolves a node's array input: the templated {@code source} config if present, otherwise the
   * upstream node's {@code items} (or {@code rows}) from the merged input.
   */
  static List<?> resolveArraySource(TemplateResolver resolver, NodeExecutionContext context) {
    Object sourceCfg = context.configValue("source");
    Object resolved;
    if (sourceCfg != null) {
      resolved = resolver.resolve(sourceCfg, context.getContext());
    } else {
      Map<String, Object> input = context.getInput();
      resolved = input.containsKey("items") ? input.get("items") : input.get("rows");
    }
    if (!(resolved instanceof List<?> list)) {
      throw new IllegalArgumentException("source did not resolve to a list");
    }
    return list;
  }

  /** Scope for evaluating an expression/template against a single array element. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> elementScope(Object element) {
    if (element instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of("value", element);
  }
}
