package com.flowmatic.auth.workflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** The model's reply is never trusted verbatim — it is cleaned, and emptiness is a failure. */
class MetaPromptServiceTest {

  private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
  private final MetaPromptService service = new MetaPromptService(chatClient);

  private void modelReplies(String reply) {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenReturn(reply);
  }

  @Test
  void returnsTheGeneratedPrompt() {
    modelReplies("Summarize each row into one sentence.");

    assertThat(service.generate("summarise my rows"))
        .isEqualTo("Summarize each row into one sentence.");
  }

  @Test
  void stripsMarkdownFencesTheModelAddsUnprompted() {
    modelReplies("```\nSummarize each row into one sentence.\n```");

    assertThat(service.generate("summarise my rows"))
        .isEqualTo("Summarize each row into one sentence.");
  }

  @Test
  void stripsLanguageTaggedFences() {
    modelReplies("```text\nSummarize each row into one sentence.\n```");

    assertThat(service.generate("summarise my rows"))
        .isEqualTo("Summarize each row into one sentence.");
  }

  @Test
  void stripsSurroundingQuotes() {
    modelReplies("\"Summarize each row into one sentence.\"");

    assertThat(service.generate("summarise my rows"))
        .isEqualTo("Summarize each row into one sentence.");
  }

  @Test
  void keepsQuotesThatAreNotWrappingTheWholePrompt() {
    modelReplies("Reply with \"yes\" or \"no\".");

    assertThat(service.generate("yes/no classifier")).isEqualTo("Reply with \"yes\" or \"no\".");
  }

  @Test
  void leavesEdgeQuotesAloneWhenTheyAreNotAMatchingPair() {
    modelReplies("\"urgent\" or \"normal\"");

    assertThat(service.generate("classify rows")).isEqualTo("\"urgent\" or \"normal\"");
  }

  @Test
  void blankResponseFails() {
    modelReplies("   \n  ");

    assertThatThrownBy(() -> service.generate("summarise my rows"))
        .isInstanceOf(MetaPromptException.class);
  }

  @Test
  void nullResponseFails() {
    modelReplies(null);

    assertThatThrownBy(() -> service.generate("summarise my rows"))
        .isInstanceOf(MetaPromptException.class);
  }

  @Test
  void modelOutageSurfacesAsMetaPromptExceptionNotTheRawCause() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenThrow(new IllegalStateException("groq 503 upstream"));

    assertThatThrownBy(() -> service.generate("summarise my rows"))
        .isInstanceOf(MetaPromptException.class)
        .hasMessageNotContaining("groq 503 upstream");
  }
}
