package com.flowmatic.auth.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.NodeRunLog;
import com.flowmatic.auth.workflow.entity.NodeRunStatus;
import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.execution.WorkflowExecutionService;
import com.flowmatic.auth.workflow.repository.NodeRunLogRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deleting a workflow must also remove its run history — the {@code workflow_runs} and {@code
 * node_run_logs} foreign keys are RESTRICT, so a bare delete fails once the workflow has ever run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowDeleteIntegrationTest {

  private static final String OWNER_EMAIL = "deleter@example.com";

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  @Autowired NodeRunLogRepository nodeRunLogRepository;
  @Autowired WorkflowExecutionService executionService;

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void deletesWorkflowThatHasRunHistory() throws Exception {
    Workflow workflow = seedWorkflowWithHistory(WorkflowRunStatus.SUCCESS);

    mockMvc
        .perform(delete("/api/workflows/{id}", workflow.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Workflow \"Untitled\" deleted"));

    assertThat(workflowRepository.findById(workflow.getId())).isEmpty();
    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .isEmpty();
  }

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void deleteWhileRunningReturns409WithAMessage() throws Exception {
    Workflow workflow = seedWorkflowWithHistory(WorkflowRunStatus.RUNNING);

    mockMvc
        .perform(delete("/api/workflows/{id}", workflow.getId()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("Workflow has a run in progress; try again once it finishes"));
  }

  @Test
  void cascadeRemovesRunsAndNodeLogs() {
    Workflow workflow = seedWorkflowWithHistory(WorkflowRunStatus.SUCCESS);
    Long runId =
        workflowRunRepository
            .findByWorkflow_IdOrderByStartedAtDesc(workflow.getId())
            .get(0)
            .getId();

    executionService.deleteWithHistory(workflow.getId());

    assertThat(workflowRepository.findById(workflow.getId())).isEmpty();
    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .isEmpty();
    assertThat(nodeRunLogRepository.findByWorkflowRun_IdOrderByStartedAtAsc(runId)).isEmpty();
  }

  @Test
  void refusesToDeleteWhileARunIsInFlight() {
    Workflow workflow = seedWorkflowWithHistory(WorkflowRunStatus.RUNNING);

    assertThatThrownBy(() -> executionService.deleteWithHistory(workflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(workflowRepository.findById(workflow.getId())).isPresent();
    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .hasSize(1);
  }

  @Test
  @WithMockUser(username = OWNER_EMAIL)
  void missingWorkflowReturns404NotAnOpaque500() throws Exception {
    seedOwner();

    mockMvc
        .perform(get("/api/workflows/{id}", 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Workflow not found"));
  }

  private User seedOwner() {
    return userRepository
        .findByEmail(OWNER_EMAIL)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(OWNER_EMAIL)
                        .fullName("Owner")
                        .passwordHash("x")
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  /**
   * A workflow with one finished run and one node log — the shape that makes a bare delete fail.
   */
  private Workflow seedWorkflowWithHistory(WorkflowRunStatus runStatus) {
    Workflow workflow =
        workflowRepository.save(
            Workflow.builder()
                .user(seedOwner())
                .name("Untitled")
                .graphJson("{\"nodes\":[],\"edges\":[]}")
                .build());

    WorkflowRun run =
        workflowRunRepository.save(
            WorkflowRun.builder()
                .workflow(workflow)
                .status(runStatus)
                .startedAt(Instant.now())
                .build());

    nodeRunLogRepository.save(
        NodeRunLog.builder()
            .workflowRun(run)
            .nodeId("manual-trigger")
            .nodeType(NodeType.TRIGGER)
            .status(NodeRunStatus.SUCCESS)
            .startedAt(Instant.now())
            .build());

    return workflow;
  }
}
