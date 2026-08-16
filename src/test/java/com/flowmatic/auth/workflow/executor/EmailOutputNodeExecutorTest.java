package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;

class EmailOutputNodeExecutorTest {

  private final ResendEmailService resendEmailService = mock(ResendEmailService.class);
  private final EmailOutputNodeExecutor executor =
      new EmailOutputNodeExecutor(
          resendEmailService, "no-reply@flowmatic.com", new TemplateResolver());

  private Map<String, Object> crmContext() {
    return Map.of(
        "ai",
        Map.of(
            "messageBody",
            "enjoy 20% off with SAVE20",
            "customers",
            List.of(
                Map.of("name", "Alice", "email", "alice@example.com"),
                Map.of("name", "Bob", "email", "bob@example.com"))));
  }

  @Test
  void forEachSendsOnePersonalizedEmailPerItem() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("out")
            .nodeType(NodeType.OUTPUT)
            .context(crmContext())
            .config(
                Map.of(
                    "forEach", "{{ai.customers}}",
                    "to", "{{item.email}}",
                    "subject", "Hi {{item.name}}",
                    "body", "{{item.name}}, {{ai.messageBody}}"))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).containsEntry("sent", 2);

    ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService, times(2))
        .send(
            anyString(),
            toCaptor.capture(),
            subjectCaptor.capture(),
            bodyCaptor.capture(),
            isNull());

    assertThat(toCaptor.getAllValues()).containsExactly("alice@example.com", "bob@example.com");
    assertThat(subjectCaptor.getAllValues()).containsExactly("Hi Alice", "Hi Bob");
    assertThat(bodyCaptor.getAllValues())
        .containsExactly("Alice, enjoy 20% off with SAVE20", "Bob, enjoy 20% off with SAVE20");
  }

  @Test
  void singleSendWithoutForEach() {
    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("out")
            .nodeType(NodeType.OUTPUT)
            .config(Map.of("to", "ops@example.com", "subject", "Report", "body", "done"))
            .build();

    executor.execute(ctx);

    verify(resendEmailService)
        .send(anyString(), eq("ops@example.com"), eq("Report"), anyString(), isNull());
  }

  @Test
  void oneFailedSendDoesNotAbortTheBatch() {
    doThrow(new RestClientException("resend down"))
        .when(resendEmailService)
        .send(anyString(), eq("bob@example.com"), anyString(), anyString(), isNull());

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi",
                        "body", "b"))
                .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).containsEntry("sent", 1);
    @SuppressWarnings("unchecked")
    List<String> failed = (List<String>) result.getOutput().get("failed");
    assertThat(failed).containsExactly("bob@example.com");
    verify(resendEmailService, times(2))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());
  }

  /** The per-message record of what actually went out, added by Change 1. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> recorded(NodeExecutionResult result) {
    return (List<Map<String, Object>>) result.getOutput().get("messages");
  }

  @Test
  void successfulBatchRecordsEveryMessageItSent() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "{{item.name}}, {{ai.messageBody}}"))
                .build());

    assertThat(result.getOutput()).containsEntry("sent", 2).containsEntry("total", 2);
    assertThat(recorded(result))
        .containsExactly(
            Map.of(
                "to", "alice@example.com",
                "subject", "Hi Alice",
                "body", "Alice, enjoy 20% off with SAVE20",
                "status", "SENT"),
            Map.of(
                "to", "bob@example.com",
                "subject", "Hi Bob",
                "body", "Bob, enjoy 20% off with SAVE20",
                "status", "SENT"));
    assertThat(result.getOutput()).doesNotContainKey("messagesTruncated");
  }

  @Test
  void failedMessageIsRecordedWithItsErrorAndTheBatchStillCompletes() {
    doThrow(new RestClientException("resend down"))
        .when(resendEmailService)
        .send(anyString(), eq("bob@example.com"), anyString(), anyString(), isNull());

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "{{ai.messageBody}}"))
                .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(recorded(result)).hasSize(2);

    // Order matches forEach iteration order, so messages[i] lines up with element i.
    assertThat(recorded(result).get(0)).containsEntry("status", "SENT").doesNotContainKey("error");
    assertThat(recorded(result).get(1))
        .containsEntry("to", "bob@example.com")
        .containsEntry("subject", "Hi Bob")
        .containsEntry("body", "enjoy 20% off with SAVE20")
        .containsEntry("status", "FAILED");
    assertThat((String) recorded(result).get(1).get("error")).contains("resend down");
  }

  @Test
  void singleSendRecordsExactlyOneMessage() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(Map.of("to", "ops@example.com", "subject", "Report", "body", "done"))
                .build());

    assertThat(recorded(result))
        .containsExactly(
            Map.of("to", "ops@example.com", "subject", "Report", "body", "done", "status", "SENT"));
  }

  @Test
  void blankSubjectIsRecordedAsTheDefaultThatWasActuallySent() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(Map.of("to", "ops@example.com", "subject", "  ", "body", "done"))
                .build());

    assertThat(recorded(result).get(0)).containsEntry("subject", "A message from Flowmatic");
  }

  @Test
  void longBodyIsCappedInTheRecordButSentInFull() {
    String longBody = "x".repeat(2500);

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(Map.of("to", "ops@example.com", "subject", "Report", "body", longBody))
                .build());

    assertThat((String) recorded(result).get(0).get("body")).hasSize(2000);
    assertThat(recorded(result).get(0)).containsEntry("bodyTruncated", true);

    // The cap bounds the run log only — the recipient still gets the whole thing.
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService)
        .send(anyString(), anyString(), anyString(), bodyCaptor.capture(), isNull());
    assertThat(bodyCaptor.getValue()).hasSize(2500);
  }

  @Test
  void recordedMessagesAreCappedButEveryEmailIsStillSent() {
    List<Map<String, Object>> many =
        IntStream.range(0, 205)
            .mapToObj(i -> Map.<String, Object>of("email", "u" + i + "@example.com"))
            .toList();

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(Map.of("ai", Map.of("customers", many)))
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi",
                        "body", "b"))
                .build());

    assertThat(recorded(result)).hasSize(200);
    assertThat(result.getOutput())
        .containsEntry("messagesTruncated", true)
        .containsEntry("sent", 205)
        .containsEntry("total", 205);
    verify(resendEmailService, times(205))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());
  }

  @Test
  void manualModeHoldsMessagesWithoutSendingAnything() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "{{item.name}}, {{ai.messageBody}}",
                        "sendMode", "manual"))
                .build());

    assertThat(result.isSuccess()).isTrue();
    verifyNoInteractions(resendEmailService);
    assertThat(result.getOutput())
        .containsEntry("sendMode", "manual")
        .containsEntry("sent", 0)
        .containsEntry("total", 2);
    assertThat(recorded(result))
        .containsExactly(
            Map.of(
                "to", "alice@example.com",
                "subject", "Hi Alice",
                "body", "Alice, enjoy 20% off with SAVE20",
                "from", "no-reply@flowmatic.com",
                "status", "PENDING"),
            Map.of(
                "to", "bob@example.com",
                "subject", "Hi Bob",
                "body", "Bob, enjoy 20% off with SAVE20",
                "from", "no-reply@flowmatic.com",
                "status", "PENDING"));
  }

  @Test
  void manualModeDoesNotTruncateBodiesBecauseTheyStillHaveToBeSentLater() {
    String longBody = "x".repeat(2500);

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(
                    Map.of(
                        "to", "ops@example.com",
                        "subject", "Report",
                        "body", longBody,
                        "sendMode", "manual"))
                .build());

    assertThat((String) recorded(result).get(0).get("body")).hasSize(2500);
    assertThat(recorded(result).get(0)).doesNotContainKey("bodyTruncated");
  }

  @Test
  void sendPendingSendsEveryPendingMessageAndRecomputesCounts() {
    NodeExecutionResult held =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "{{item.name}}, {{ai.messageBody}}",
                        "sendMode", "manual"))
                .build());

    Map<String, Object> settled = executor.sendPending(held.getOutput());

    assertThat(settled)
        .containsEntry("sent", 2)
        .containsEntry("total", 2)
        .doesNotContainKey("messagesTruncated");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) settled.get("messages");
    assertThat(messages).allMatch(m -> "SENT".equals(m.get("status")));
    // Settled entries match the pre-existing (Change 1) shape — no leftover "from" field.
    assertThat(messages.get(0)).doesNotContainKey("from");

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService, times(2))
        .send(anyString(), anyString(), anyString(), bodyCaptor.capture(), isNull());
    assertThat(bodyCaptor.getAllValues().get(0)).isEqualTo("Alice, enjoy 20% off with SAVE20");
  }

  @Test
  void sendPendingHandlesAPartialFailure() {
    doThrow(new RestClientException("resend down"))
        .when(resendEmailService)
        .send(anyString(), eq("bob@example.com"), anyString(), anyString(), isNull());

    NodeExecutionResult held =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "b",
                        "sendMode", "manual"))
                .build());

    Map<String, Object> settled = executor.sendPending(held.getOutput());

    assertThat(settled).containsEntry("sent", 1);
    @SuppressWarnings("unchecked")
    List<String> failed = (List<String>) settled.get("failed");
    assertThat(failed).containsExactly("bob@example.com");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) settled.get("messages");
    assertThat(messages.get(1)).containsEntry("status", "FAILED");
    assertThat((String) messages.get(1).get("error")).contains("resend down");
  }

  @Test
  void sendPendingIsIdempotentSoARetryOnlyResendsWhatIsStillPending() {
    NodeExecutionResult held =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .context(crmContext())
                .config(
                    Map.of(
                        "forEach", "{{ai.customers}}",
                        "to", "{{item.email}}",
                        "subject", "Hi {{item.name}}",
                        "body", "b",
                        "sendMode", "manual"))
                .build());

    Map<String, Object> firstPass = executor.sendPending(held.getOutput());
    Map<String, Object> secondPass = executor.sendPending(firstPass);

    assertThat(secondPass).containsEntry("sent", 2).containsEntry("total", 2);
    // Not 4 — the second pass found nothing PENDING left and resent nothing.
    verify(resendEmailService, times(2))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());
  }

  @Test
  void sendPendingTruncatesLongBodiesInTheFinalRecordOnceSettled() {
    String longBody = "x".repeat(2500);
    NodeExecutionResult held =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(
                    Map.of(
                        "to", "ops@example.com",
                        "subject", "Report",
                        "body", longBody,
                        "sendMode", "manual"))
                .build());

    Map<String, Object> settled = executor.sendPending(held.getOutput());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) settled.get("messages");
    assertThat((String) messages.get(0).get("body")).hasSize(2000);
    assertThat(messages.get(0)).containsEntry("bodyTruncated", true);

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService)
        .send(anyString(), anyString(), anyString(), bodyCaptor.capture(), isNull());
    assertThat(bodyCaptor.getValue()).hasSize(2500); // sent in full despite the capped record
  }

  @Test
  void missingToFails() {
    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("out")
                .nodeType(NodeType.OUTPUT)
                .config(Map.of("body", "b"))
                .build());
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("to");
  }
}
