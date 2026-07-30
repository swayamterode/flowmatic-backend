package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformNodeExecutorTest {

  private final TransformNodeExecutor executor = new TransformNodeExecutor(new TemplateResolver());

  @Test
  void mapsEachElementOfAListSource() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("t")
            .nodeType(NodeType.TRANSFORM)
            .context(
                Map.of(
                    "ds",
                    Map.of(
                        "rows",
                        List.of(
                            Map.of("name", "Alice", "email", "a@x.com"),
                            Map.of("name", "Bob", "email", "b@x.com")))))
            .config(
                Map.of(
                    "source",
                    "{{ds.rows}}",
                    "map",
                    Map.of("label", "{{item.name}} <{{item.email}}>")))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    assertThat(result.isSuccess()).isTrue();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.getOutput().get("items");
    assertThat(items)
        .extracting(m -> m.get("label"))
        .containsExactly("Alice <a@x.com>", "Bob <b@x.com>");
  }

  @Test
  void singleResultWhenSourceIsNotAList() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("t")
            .nodeType(NodeType.TRANSFORM)
            .context(Map.of("ai", Map.of("summary", "ok", "priority", "high")))
            .config(Map.of("map", Map.of("headline", "{{ai.priority}}: {{ai.summary}}")))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    assertThat(result.isSuccess()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) result.getOutput().get("result");
    assertThat(res).containsEntry("headline", "high: ok");
  }

  @Test
  void missingMapFails() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("t")
                .nodeType(NodeType.TRANSFORM)
                .config(Map.of())
                .build());
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("map");
  }
}
