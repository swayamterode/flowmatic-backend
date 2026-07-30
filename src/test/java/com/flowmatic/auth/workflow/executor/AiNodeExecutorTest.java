package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class AiNodeExecutorTest {

  private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
  private final AiNodeExecutor executor = new AiNodeExecutor(chatClient, new TemplateResolver());

  private NodeExecutionContext.NodeExecutionContextBuilder base() {
    return NodeExecutionContext.builder()
        .nodeId("ai")
        .nodeType(NodeType.AI)
        .context(Map.of("ds", Map.of("rows", List.of(Map.of("name", "Alice", "rating", "5")))));
  }

  @Test
  void noSchemaReturnsText() {
    when(chatClient.prompt().user(anyString()).call().content()).thenReturn("a plain summary");

    NodeExecutionResult result =
        executor.execute(base().config(Map.of("prompt", "Summarize {{ds.rows}}")).build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).containsEntry("text", "a plain summary");
  }

  @Test
  void withSchemaReturnsStructuredFields() {
    when(chatClient.prompt().user(anyString()).call().content())
        .thenReturn("{\"summary\":\"all good\",\"priority\":\"high\"}");

    NodeExecutionResult result =
        executor.execute(
            base()
                .config(
                    Map.of(
                        "prompt",
                        "Classify {{ds.rows}}",
                        "output",
                        List.of(
                            Map.of("name", "summary", "type", "string"),
                            Map.of("name", "priority", "type", "string"))))
                .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput())
        .containsEntry("summary", "all good")
        .containsEntry("priority", "high");
  }

  @Test
  void malformedJsonWithSchemaFailsAndKeepsRaw() {
    String garbage = "sorry, not JSON";
    when(chatClient.prompt().user(anyString()).call().content()).thenReturn(garbage);

    NodeExecutionResult result =
        executor.execute(
            base()
                .config(
                    Map.of(
                        "prompt",
                        "Classify {{ds.rows}}",
                        "output",
                        List.of(Map.of("name", "summary", "type", "string"))))
                .build());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("Failed to parse");
    assertThat(result.getRawDetail()).isEqualTo(garbage);
  }

  @Test
  void missingPromptFails() {
    NodeExecutionResult result = executor.execute(base().config(Map.of()).build());
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("prompt");
  }
}
