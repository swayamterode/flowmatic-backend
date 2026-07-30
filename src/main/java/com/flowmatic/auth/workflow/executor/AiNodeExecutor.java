package com.flowmatic.auth.workflow.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Generic AI executor. The prompt is a template that can reference any upstream node ({@code
 * {{ds.rows}}}). If the config defines an {@code output} schema (a list of {@code {name,type}}),
 * the model is asked for a JSON object with those fields and the parsed object becomes the node
 * output ({@code {{ai.field}}}); otherwise the raw text is returned as {@code {{ai.text}}}.
 *
 * <p>On a parse failure the raw model response is preserved to {@code node_run_logs.error_message}.
 */
@Component
public class AiNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(AiNodeExecutor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ChatClient chatClient;
  private final TemplateResolver templateResolver;

  public AiNodeExecutor(ChatClient workflowChatClient, TemplateResolver templateResolver) {
    this.chatClient = workflowChatClient;
    this.templateResolver = templateResolver;
  }

  @Override
  public NodeType supports() {
    return NodeType.AI;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    Object promptCfg = context.configValue("prompt");
    if (promptCfg == null || promptCfg.toString().isBlank()) {
      return NodeExecutionResult.failure("AI node requires config 'prompt'");
    }

    String prompt;
    try {
      prompt = templateResolver.resolveToString(promptCfg, context.getContext());
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("Prompt template error: " + e.getMessage());
    }

    List<Map<String, Object>> schema = outputSchema(context.configValue("output"));
    String fullPrompt = schema.isEmpty() ? prompt : prompt + "\n\n" + formatInstructions(schema);

    String raw;
    try {
      raw = chatClient.prompt().user(fullPrompt).call().content();
    } catch (Exception e) {
      log.error("AI node {} model call failed", context.getNodeId(), e);
      return NodeExecutionResult.failure("Model call failed: " + e.getMessage());
    }

    if (schema.isEmpty()) {
      return NodeExecutionResult.success(Map.of("text", raw == null ? "" : raw));
    }

    try {
      Map<String, Object> parsed =
          MAPPER.readValue(stripFences(raw), new TypeReference<Map<String, Object>>() {});
      return NodeExecutionResult.success(parsed);
    } catch (Exception e) {
      log.warn(
          "AI node {} could not parse model response: {}", context.getNodeId(), e.getMessage());
      return NodeExecutionResult.failure(
          "Failed to parse model response as JSON object: " + e.getMessage(), raw);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> outputSchema(Object cfg) {
    if (cfg instanceof List<?> list) {
      return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
    }
    return List.of();
  }

  private static String formatInstructions(List<Map<String, Object>> schema) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "Respond with ONLY a JSON object (no markdown fences, no prose) with these fields:\n");
    for (Map<String, Object> field : schema) {
      Object name = field.get("name");
      Object type = field.getOrDefault("type", "string");
      sb.append("- \"").append(name).append("\": ").append(type).append("\n");
    }
    return sb.toString();
  }

  /** Strips ```json ... ``` fences some models wrap JSON in. */
  private static String stripFences(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (t.startsWith("```")) {
      int firstNewline = t.indexOf('\n');
      if (firstNewline > 0) {
        t = t.substring(firstNewline + 1);
      }
      if (t.endsWith("```")) {
        t = t.substring(0, t.length() - 3);
      }
    }
    return t.trim();
  }
}
