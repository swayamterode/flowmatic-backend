package com.flowmatic.auth.workflow.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Rewrites a loose user description ("i want to email customers who havent paid") into a short,
 * self-contained prompt the user can save into a workflow AI node.
 *
 * <p>Reuses the shared {@code workflowChatClient}, which points at Groq through the Spring AI
 * OpenAI-compatible client.
 */
@Service
public class MetaPromptService {

  private static final Logger log = LoggerFactory.getLogger(MetaPromptService.class);

  private static final String SYSTEM_PROMPT =
      """
      You are a prompt engineer. Rewrite the user's description of what they want an AI to do \
      into one short, self-contained instruction prompt for a language model.

      Rules:
      - At most 3 sentences and under 60 words.
      - Address the model directly as an instruction; do not describe the user.
      - Keep every concrete detail the user gave. Invent no new requirements.
      - Output ONLY the prompt text: no preamble, no explanation, no surrounding quotes, \
      no markdown fences.
      """;

  private final ChatClient chatClient;

  public MetaPromptService(ChatClient workflowChatClient) {
    this.chatClient = workflowChatClient;
  }

  /**
   * @return the generated prompt, cleaned of the wrappers models add unbidden
   * @throws MetaPromptException if the model call fails or yields nothing usable
   */
  public String generate(String message) {
    String raw;
    try {
      raw = chatClient.prompt().system(SYSTEM_PROMPT).user(message).call().content();
    } catch (Exception e) {
      log.error("Meta-prompt model call failed", e);
      throw new MetaPromptException("Prompt generation failed", e);
    }

    String cleaned = unquote(stripFences(raw));
    if (cleaned.isBlank()) {
      log.warn("Model returned a blank meta-prompt");
      throw new MetaPromptException("Prompt generation failed");
    }
    return cleaned;
  }

  /** Strips ``` or ```text fences the model wraps output in despite being told not to. */
  private static String stripFences(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (!t.startsWith("```")) {
      return t;
    }
    int firstNewline = t.indexOf('\n');
    if (firstNewline > 0) {
      t = t.substring(firstNewline + 1);
    }
    if (t.endsWith("```")) {
      t = t.substring(0, t.length() - 3);
    }
    return t.trim();
  }

  /**
   * Drops quotes wrapping the whole prompt, leaving quotes used inside it alone. An interior quote
   * means the outer two are not a pair (e.g. {@code "urgent" or "normal"}), so nothing is stripped.
   */
  private static String unquote(String s) {
    if (s.length() < 2 || !s.startsWith("\"") || !s.endsWith("\"")) {
      return s;
    }
    String interior = s.substring(1, s.length() - 1);
    return interior.contains("\"") ? s : interior.trim();
  }
}
