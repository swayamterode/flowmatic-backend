package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reshapes data. {@code map} defines the output object from templates that may reference the
 * current element ({@code {{item.*}}}) and any upstream node. If {@code source} is a list, the map
 * is applied per element → {@code {items:[...]}}; otherwise it is applied once → {@code
 * {result:{...}}}.
 */
@Component
public class TransformNodeExecutor implements NodeExecutor {

  private static final String ITEM = "item";

  private final TemplateResolver templateResolver;

  public TransformNodeExecutor(TemplateResolver templateResolver) {
    this.templateResolver = templateResolver;
  }

  @Override
  public NodeType supports() {
    return NodeType.TRANSFORM;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    Object mapCfg = context.configValue("map");
    if (!(mapCfg instanceof Map)) {
      return NodeExecutionResult.failure("TRANSFORM node requires an object config 'map'");
    }

    Object sourceCfg = context.configValue("source");
    Object source =
        sourceCfg == null ? null : templateResolver.resolve(sourceCfg, context.getContext());

    try {
      if (source instanceof List<?> list) {
        List<Object> items = new ArrayList<>();
        for (Object element : list) {
          items.add(templateResolver.resolve(mapCfg, scopeWithItem(context, element)));
        }
        return NodeExecutionResult.success(Map.of("items", items));
      }
      Object result = templateResolver.resolve(mapCfg, scopeWithItem(context, source));
      return NodeExecutionResult.success(Map.of("result", result));
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("TRANSFORM error: " + e.getMessage());
    }
  }

  private Map<String, Object> scopeWithItem(NodeExecutionContext context, Object element) {
    Map<String, Object> scope = new LinkedHashMap<>(context.getContext());
    scope.put(ITEM, element);
    return scope;
  }
}
