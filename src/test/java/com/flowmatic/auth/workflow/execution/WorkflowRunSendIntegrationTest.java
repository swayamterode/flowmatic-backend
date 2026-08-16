package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.NodeRunLog;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.repository.NodeRunLogRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The manual-review "Send" action: {@code POST /api/workflows/runs/{runId}/nodes/{nodeId}/send}.
 * Proves the promise the feature exists for — a manual-mode OUTPUT node never touches {@link
 * ResendEmailService} on its own, only once this endpoint is called by the run's owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowRunSendIntegrationTest {

  private static final String OWNER_EMAIL = "sender-owner@example.com";
  private static final String OTHER_EMAIL = "sender-other@example.com";

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  @Autowired NodeRunLogRepository nodeRunLogRepository;
  @Autowired WorkflowExecutionService executionService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private User user(String email) {
    return userRepository
        .findByEmail(email)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(email)
                        .fullName("Owner")
                        .passwordHash("x")
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  /** A one-node workflow whose OUTPUT node sends (or holds) a single email, already run. */
  private WorkflowRun ranWorkflowWithOneEmail(User owner, String sendMode) throws Exception {
    Map<String, Object> outputData =
        sendMode == null
            ? Map.of("to", "customer@example.com", "subject", "Hi", "body", "hello")
            : Map.of(
                "to",
                "customer@example.com",
                "subject",
                "Hi",
                "body",
                "hello",
                "sendMode",
                sendMode);

    Map<String, Object> graph =
        Map.of(
            "nodes",
                List.of(
                    Map.of("id", "t", "type", "TRIGGER", "data", Map.of()),
                    Map.of("id", "out", "type", "OUTPUT", "data", outputData)),
            "edges", List.of(Map.of("source", "t", "target", "out")));

    Workflow workflow =
        workflowRepository.save(
            Workflow.builder()
                .user(owner)
                .name("Single email")
                .graphJson(objectMapper.writeValueAsString(graph))
                .build());

    WorkflowRun run = executionService.enqueue(workflow.getId());
    while (executionService.runNextPending()) {
      // drain
    }
    return workflowRunRepository.findById(run.getId()).orElseThrow();
  }

  private NodeRunLog outputLog(WorkflowRun run) {
    Map<String, NodeRunLog> byId =
        nodeRunLogRepository.findByWorkflowRun_IdOrderByStartedAtAsc(run.getId()).stream()
            .collect(Collectors.toMap(NodeRunLog::getNodeId, Function.identity()));
    return byId.get("out");
  }

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void sendsEveryHeldMessageAndReturnsTheUpdatedNode() throws Exception {
    WorkflowRun run = ranWorkflowWithOneEmail(user(OWNER_EMAIL), "manual");
    assertThat(outputLog(run).getOutputJson()).contains("\"status\":\"PENDING\"");
    verifyNoInteractions(resendEmailService);

    mockMvc
        .perform(post("/api/workflows/runs/{runId}/nodes/{nodeId}/send", run.getId(), "out"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.output.sent").value(1))
        .andExpect(jsonPath("$.output.messages[0].status").value("SENT"));

    verify(resendEmailService, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());
    assertThat(outputLog(run).getOutputJson()).contains("\"status\":\"SENT\"");
  }

  @Test
  @WithMockUser(username = OTHER_EMAIL)
  void refusesToSendAnotherOwnersRun() throws Exception {
    WorkflowRun run = ranWorkflowWithOneEmail(user(OWNER_EMAIL), "manual");
    user(OTHER_EMAIL);

    mockMvc
        .perform(post("/api/workflows/runs/{runId}/nodes/{nodeId}/send", run.getId(), "out"))
        .andExpect(status().isNotFound());

    verifyNoInteractions(resendEmailService);
  }

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void refusesAnUnknownNodeIdWithinAnOwnedRun() throws Exception {
    WorkflowRun run = ranWorkflowWithOneEmail(user(OWNER_EMAIL), "manual");

    mockMvc
        .perform(post("/api/workflows/runs/{runId}/nodes/{nodeId}/send", run.getId(), "nope"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void refusesANodeThatWasNeverHeldForManualReview() throws Exception {
    // sendMode omitted entirely -> auto mode -> already sent during the run itself.
    WorkflowRun run = ranWorkflowWithOneEmail(user(OWNER_EMAIL), null);
    verify(resendEmailService, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());

    mockMvc
        .perform(post("/api/workflows/runs/{runId}/nodes/{nodeId}/send", run.getId(), "out"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("This node has no pending messages to send"));

    // Still exactly one — the conflicting call must not have sent a second copy.
    verify(resendEmailService, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), isNull());
  }
}
