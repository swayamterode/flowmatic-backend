package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.ExecutionRowDTO;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "dash-empty@example.com")
  void returnsZeroFilledRowsForNewUserWithNoRuns() throws Exception {
    saveUser("dash-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-over-time?days=7"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<ExecutionRowDTO> rows =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, ExecutionRowDTO.class));
    assertThat(rows).hasSize(7);
    assertThat(rows).allMatch(r -> r.executions() == 0);
  }

  @Test
  @WithMockUser(username = "dash-owner@example.com")
  void countsOnlyTheCallersOwnRuns() throws Exception {
    User owner = saveUser("dash-owner@example.com");
    User other = saveUser("dash-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, Instant.now());
    saveRun(otherWorkflow, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/executions-over-time?days=7"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<ExecutionRowDTO> rows =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, ExecutionRowDTO.class));
    long total = rows.stream().mapToLong(ExecutionRowDTO::executions).sum();
    assertThat(total).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "dash-badrange@example.com")
  void rejectsUnsupportedDaysValue() throws Exception {
    saveUser("dash-badrange@example.com");

    mockMvc
        .perform(get("/api/dashboard/executions-over-time?days=14"))
        .andExpect(status().isBadRequest());
  }

  private User saveUser(String email) {
    return userRepository
        .findByEmail(email)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(email)
                        .fullName("Dash User")
                        .passwordHash("x")
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  private Workflow saveWorkflow(User user) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  private void saveRun(Workflow workflow, Instant startedAt) {
    workflowRunRepository.save(
        WorkflowRun.builder()
            .workflow(workflow)
            .status(WorkflowRunStatus.SUCCESS)
            .startedAt(startedAt)
            .completedAt(startedAt)
            .build());
  }
}
