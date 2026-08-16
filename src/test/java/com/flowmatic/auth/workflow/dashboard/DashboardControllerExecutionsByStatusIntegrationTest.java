package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.dashboard.dto.StatusBreakdownDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerExecutionsByStatusIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "status-empty@example.com")
  void returnsAllFourStatusesAtZeroForNewUserWithNoRuns() throws Exception {
    saveUser("status-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, StatusBreakdownDTO.class));
    assertThat(result).hasSize(4);
    assertThat(result).allMatch(r -> r.count() == 0);
  }

  @Test
  @WithMockUser(username = "status-owner@example.com")
  void countsOnlyTheCallersOwnRuns() throws Exception {
    User owner = saveUser("status-owner@example.com");
    User other = saveUser("status-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(ownerWorkflow, WorkflowRunStatus.FAILED, Instant.now());
    saveRun(otherWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, StatusBreakdownDTO.class));
    long success =
        result.stream().filter(r -> r.status().equals("SUCCESS")).findFirst().get().count();
    long failed =
        result.stream().filter(r -> r.status().equals("FAILED")).findFirst().get().count();
    assertThat(success).isEqualTo(2);
    assertThat(failed).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "status-old@example.com")
  void excludesRunsOlderThanThirtyDays() throws Exception {
    User owner = saveUser("status-old@example.com");
    Workflow workflow = saveWorkflow(owner);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(
        workflow, WorkflowRunStatus.SUCCESS, Instant.now().minus(java.time.Duration.ofDays(45)));

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-by-status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<StatusBreakdownDTO> result =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, StatusBreakdownDTO.class));
    long success =
        result.stream().filter(r -> r.status().equals("SUCCESS")).findFirst().get().count();
    assertThat(success).isEqualTo(1);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Status User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  private void saveRun(Workflow workflow, WorkflowRunStatus status, Instant startedAt) {
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(status)
            .startedAt(startedAt)
            .completedAt(startedAt)
            .build());
  }
}
