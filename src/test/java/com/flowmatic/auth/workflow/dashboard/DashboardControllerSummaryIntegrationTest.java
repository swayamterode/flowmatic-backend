package com.flowmatic.auth.workflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.dashboard.dto.SummaryStatsDTO;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
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
class DashboardControllerSummaryIntegrationTest {

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "summary-empty@example.com")
  void returnsZerosAndNullsForNewUserWithNoRuns() throws Exception {
    saveUser("summary-empty@example.com");

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.executionsToday()).isEqualTo(0);
    assertThat(stats.executionsTodayDeltaPct()).isNull();
    assertThat(stats.successRatePct()).isNull();
    assertThat(stats.successRateDeltaPp()).isNull();
    assertThat(stats.failedRuns()).isEqualTo(0);
    assertThat(stats.failedRunsDeltaPct()).isNull();
    assertThat(stats.medianRunTimeSeconds()).isNull();
    assertThat(stats.medianRunTimeDeltaPct()).isNull();
  }

  @Test
  @WithMockUser(username = "summary-owner@example.com")
  void countsOnlyTheCallersOwnRunsToday() throws Exception {
    User owner = saveUser("summary-owner@example.com");
    User other = saveUser("summary-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);
    saveRun(ownerWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(otherWorkflow, WorkflowRunStatus.SUCCESS, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.executionsToday()).isEqualTo(1);
  }

  @Test
  @WithMockUser(username = "summary-rate@example.com")
  void computesSuccessRateOverTheCallersRunsThisWeek() throws Exception {
    User owner = saveUser("summary-rate@example.com");
    Workflow workflow = saveWorkflow(owner);
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.SUCCESS, Instant.now());
    saveRun(workflow, WorkflowRunStatus.FAILED, Instant.now());

    String body =
        mockMvc
            .perform(get("/api/dashboard/summary"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    SummaryStatsDTO stats = objectMapper.readValue(body, SummaryStatsDTO.class);
    assertThat(stats.successRatePct()).isEqualTo(75.0);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Summary User")
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
