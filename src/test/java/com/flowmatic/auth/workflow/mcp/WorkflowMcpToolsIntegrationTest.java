package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowMcpToolsIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired WorkflowMcpTools workflowMcpTools;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("MCP Test User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String name) {
    return workflowRepository.save(
        Workflow.builder().user(user).name(name).graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }

  @Test
  @WithMockUser(username = "mcp-tools-a@example.com")
  void listWorkflowsReturnsOnlyTheCallersWorkflows() {
    User owner = saveUser("mcp-tools-a@example.com");
    User other = saveUser("mcp-tools-b@example.com");
    saveWorkflow(owner, "owner-workflow");
    saveWorkflow(other, "other-workflow");

    List<Map<String, Object>> result = workflowMcpTools.listWorkflows();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("name")).isEqualTo("owner-workflow");
  }

  @Test
  @WithMockUser(username = "mcp-tools-c@example.com")
  void getWorkflowReturnsTheGraphForAnOwnedWorkflow() {
    User owner = saveUser("mcp-tools-c@example.com");
    Workflow workflow = saveWorkflow(owner, "graph-workflow");

    Map<String, Object> result = workflowMcpTools.getWorkflow(workflow.getId());

    assertThat(result.get("name")).isEqualTo("graph-workflow");
    assertThat(result).containsKey("graph");
  }

  @Test
  @WithMockUser(username = "mcp-tools-d@example.com")
  void getWorkflowThrowsNotFoundForAnotherUsersWorkflow() {
    saveUser("mcp-tools-d@example.com");
    User other = saveUser("mcp-tools-e@example.com");
    Workflow othersWorkflow = saveWorkflow(other, "not-yours");

    assertThatThrownBy(() -> workflowMcpTools.getWorkflow(othersWorkflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Workflow not found");
  }

  @Test
  @WithMockUser(username = "mcp-tools-f@example.com")
  void runWorkflowEnqueuesAPendingRun() {
    User owner = saveUser("mcp-tools-f@example.com");
    Workflow workflow = saveWorkflow(owner, "run-me");

    Map<String, Object> result = workflowMcpTools.runWorkflow(workflow.getId());

    assertThat(result.get("status")).isEqualTo(WorkflowRunStatus.PENDING);
  }

  @Test
  @WithMockUser(username = "mcp-tools-g@example.com")
  void getDashboardSummaryReturnsZerosForANewUserWithNoRuns() {
    saveUser("mcp-tools-g@example.com");

    var result = workflowMcpTools.getDashboardSummary();

    assertThat(result.executionsToday()).isEqualTo(0);
  }
}
