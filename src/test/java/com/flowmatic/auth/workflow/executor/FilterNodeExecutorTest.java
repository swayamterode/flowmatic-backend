package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.ExpressionEvaluator;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterNodeExecutorTest {

  private final FilterNodeExecutor executor =
      new FilterNodeExecutor(new TemplateResolver(), new ExpressionEvaluator());

  private static final List<Map<String, Object>> ROWS =
      List.of(
          Map.of("name", "Alice", "rating", "5"),
          Map.of("name", "Bob", "rating", "4"),
          Map.of("name", "Carol", "rating", "2"));

  @Test
  void filtersDefaultUpstreamRowsByExpr() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("f")
            .nodeType(NodeType.FILTER)
            .input(Map.of("rows", ROWS))
            .config(Map.of("expr", "rating > 3"))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    assertThat(result.isSuccess()).isTrue();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.getOutput().get("items");
    assertThat(items).extracting(m -> m.get("name")).containsExactly("Alice", "Bob");
  }

  @Test
  void filtersExplicitSource() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("f")
            .nodeType(NodeType.FILTER)
            .context(Map.of("ds", Map.of("rows", ROWS)))
            .config(Map.of("source", "{{ds.rows}}", "expr", "rating == 5"))
            .build();

    NodeExecutionResult result = executor.execute(ctx);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.getOutput().get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).containsEntry("name", "Alice");
  }

  @Test
  void missingExprFails() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("f")
                .nodeType(NodeType.FILTER)
                .input(Map.of("rows", ROWS))
                .config(Map.of())
                .build());
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("expr");
  }
}
