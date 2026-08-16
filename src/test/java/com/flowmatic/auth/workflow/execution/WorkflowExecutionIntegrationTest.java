package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.NodeRunLog;
import com.flowmatic.auth.workflow.entity.NodeRunStatus;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.NodeRunLogRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import com.flowmatic.auth.workflow.upload.UploadStorage;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end tests on the GENERIC engine. Proves the old CRM flow is re-expressible with generic,
 * config-driven nodes, and that CONDITION branching skips the untaken branch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(WorkflowExecutionIntegrationTest.AiTestConfig.class)
class WorkflowExecutionIntegrationTest {

  static final String AI_JSON =
      """
      {
        "customers": [
          {"name": "Alice Johnson", "email": "alice@example.com", "reason": "rated 5 stars"},
          {"name": "Dave Brown", "email": "dave@example.com", "reason": "rated 5 stars"}
        ],
        "messageBody": "thanks for the great review! Enjoy SAVE20."
      }
      """;

  @TestConfiguration
  static class AiTestConfig {
    @Bean
    ChatClient workflowChatClient() {
      ChatClient mock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
      when(mock.prompt().user(anyString()).call().content()).thenReturn(AI_JSON);
      return mock;
    }
  }

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  @Autowired NodeRunLogRepository nodeRunLogRepository;
  @Autowired WorkflowExecutionService executionService;
  @Autowired UploadStorage uploadStorage;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private User newUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String name, Map<String, Object> graph)
      throws Exception {
    return workflowRepository.save(
        Workflow.builder()
            .user(user)
            .name(name)
            .graphJson(objectMapper.writeValueAsString(graph))
            .build());
  }

  @Test
  void crmFlowExpressedWithGenericNodes() throws Exception {
    byte[] csv = getClass().getResourceAsStream("/fixtures/customers.csv").readAllBytes();
    String uploadId = uploadStorage.store("customers.csv", csv);
    User user = newUser("crm@example.com");

    Map<String, Object> graph =
        Map.of(
            "nodes",
                List.of(
                    Map.of("id", "t", "type", "TRIGGER", "data", Map.of()),
                    Map.of("id", "ds", "type", "DATA_SOURCE", "data", Map.of("uploadId", uploadId)),
                    Map.of(
                        "id",
                        "ai",
                        "type",
                        "AI",
                        "data",
                        Map.of(
                            "prompt",
                            "Find customers rated above 4 in {{ds.rows}}",
                            "output",
                            List.of(
                                Map.of("name", "customers", "type", "array"),
                                Map.of("name", "messageBody", "type", "string")))),
                    Map.of(
                        "id",
                        "out",
                        "type",
                        "OUTPUT",
                        "data",
                        Map.of(
                            "forEach", "{{ai.customers}}",
                            "to", "{{item.email}}",
                            "subject", "Thanks {{item.name}}",
                            "body", "{{item.name}}, {{ai.messageBody}}"))),
            "edges",
                List.of(
                    Map.of("source", "t", "target", "ds"),
                    Map.of("source", "ds", "target", "ai"),
                    Map.of("source", "ai", "target", "out")));

    Workflow workflow = saveWorkflow(user, "Reward top raters", graph);
    WorkflowRun run = runAndFetch(workflow);

    assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
    Map<String, NodeRunLog> byId = logsById(run);
    assertThat(byId.keySet()).containsExactlyInAnyOrder("t", "ds", "ai", "out");
    assertThat(byId.values()).allMatch(l -> l.getStatus() == NodeRunStatus.SUCCESS);

    assertThat((List<?>) readJson(byId.get("ds").getOutputJson()).get("rows")).hasSize(6);
    Map<String, Object> aiOut = readJson(byId.get("ai").getOutputJson());
    assertThat((List<?>) aiOut.get("customers")).hasSize(2);
    assertThat(((Number) readJson(byId.get("out").getOutputJson()).get("sent")).intValue())
        .isEqualTo(2);

    var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(resendEmailService, times(2))
        .send(
            anyString(),
            anyString(),
            anyString(),
            bodyCaptor.capture(),
            org.mockito.ArgumentMatchers.isNull());
    assertThat(bodyCaptor.getAllValues().get(0))
        .isEqualTo("Alice Johnson, thanks for the great review! Enjoy SAVE20.");
  }

  @Test
  void conditionSkipsUntakenBranch() throws Exception {
    User user = newUser("branch@example.com");

    Map<String, Object> graph =
        Map.of(
            "nodes",
                List.of(
                    Map.of(
                        "id",
                        "t",
                        "type",
                        "TRIGGER",
                        "data",
                        Map.of("payload", Map.of("priority", "high"))),
                    Map.of(
                        "id",
                        "c",
                        "type",
                        "CONDITION",
                        "data",
                        Map.of("expr", "t.priority == 'high'")),
                    Map.of(
                        "id",
                        "email",
                        "type",
                        "OUTPUT",
                        "data",
                        Map.of(
                            "to", "team@example.com", "subject", "Escalate", "body", "handle it")),
                    Map.of(
                        "id",
                        "log",
                        "type",
                        "HTTP",
                        "data",
                        Map.of("method", "POST", "url", "https://example.com/never"))),
            "edges",
                List.of(
                    Map.of("source", "t", "target", "c"),
                    Map.of("source", "c", "target", "email", "sourceHandle", "true"),
                    Map.of("source", "c", "target", "log", "sourceHandle", "false")));

    Workflow workflow = saveWorkflow(user, "Escalate high priority", graph);
    WorkflowRun run = runAndFetch(workflow);

    assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
    Map<String, NodeRunLog> byId = logsById(run);
    assertThat(byId.get("t").getStatus()).isEqualTo(NodeRunStatus.SUCCESS);
    assertThat(byId.get("c").getStatus()).isEqualTo(NodeRunStatus.SUCCESS);
    assertThat(byId.get("email").getStatus()).isEqualTo(NodeRunStatus.SUCCESS);
    // The false branch must be skipped (and its HTTP call never made).
    assertThat(byId.get("log").getStatus()).isEqualTo(NodeRunStatus.SKIPPED);
    verify(resendEmailService, times(1))
        .send(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void queueEnqueuesPendingThenDrainsAllSequentially() throws Exception {
    User user = newUser("queue@example.com");
    Map<String, Object> graph =
        Map.of(
            "nodes", List.of(Map.of("id", "t", "type", "TRIGGER", "data", Map.of())),
            "edges", List.of());
    Workflow wf = saveWorkflow(user, "trivial", graph);

    WorkflowRun r1 = executionService.enqueue(wf.getId());
    WorkflowRun r2 = executionService.enqueue(wf.getId());
    WorkflowRun r3 = executionService.enqueue(wf.getId());

    // Enqueued runs sit PENDING until the drainer runs them.
    assertThat(workflowRunRepository.findById(r1.getId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowRunStatus.PENDING);

    int processed = 0;
    while (executionService.runNextPending()) {
      processed++;
    }
    assertThat(processed).isGreaterThanOrEqualTo(3);

    for (WorkflowRun r : List.of(r1, r2, r3)) {
      assertThat(workflowRunRepository.findById(r.getId()).orElseThrow().getStatus())
          .isEqualTo(WorkflowRunStatus.SUCCESS);
    }
  }

  /** Enqueue then drain the queue, returning the reloaded run (mirrors the scheduler). */
  private WorkflowRun runAndFetch(Workflow workflow) {
    WorkflowRun run = executionService.enqueue(workflow.getId());
    while (executionService.runNextPending()) {
      // drain
    }
    return workflowRunRepository.findById(run.getId()).orElseThrow();
  }

  private Map<String, NodeRunLog> logsById(WorkflowRun run) {
    return nodeRunLogRepository.findByWorkflowRun_IdOrderByStartedAtAsc(run.getId()).stream()
        .collect(Collectors.toMap(NodeRunLog::getNodeId, Function.identity()));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJson(String json) throws Exception {
    return objectMapper.readValue(json, Map.class);
  }
}
