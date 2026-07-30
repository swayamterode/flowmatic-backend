package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailOutputNodeExecutorTest {

  private final JavaMailSender mailSender = mock(JavaMailSender.class);
  private final EmailOutputNodeExecutor executor =
      new EmailOutputNodeExecutor(mailSender, "no-reply@flowmatic.com", new TemplateResolver());

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

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, times(2)).send(captor.capture());
    List<SimpleMailMessage> messages = captor.getAllValues();
    assertThat(messages.get(0).getTo()).containsExactly("alice@example.com");
    assertThat(messages.get(0).getSubject()).isEqualTo("Hi Alice");
    assertThat(messages.get(0).getText()).isEqualTo("Alice, enjoy 20% off with SAVE20");
    assertThat(messages.get(1).getText()).isEqualTo("Bob, enjoy 20% off with SAVE20");
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

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    assertThat(captor.getValue().getTo()).containsExactly("ops@example.com");
    assertThat(captor.getValue().getSubject()).isEqualTo("Report");
  }

  @Test
  void oneFailedSendDoesNotAbortTheBatch() {
    doThrow(new MailSendException("smtp down"))
        .when(mailSender)
        .send(
            argThat(
                (SimpleMailMessage m) ->
                    m != null && m.getTo() != null && "bob@example.com".equals(m.getTo()[0])));

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
    verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
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
