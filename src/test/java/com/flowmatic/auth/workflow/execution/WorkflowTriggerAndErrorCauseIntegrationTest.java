package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.ErrorCause;
import com.flowmatic.auth.workflow.entity.TriggerType;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowTriggerAndErrorCauseIntegrationTest {

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowExecutionService executionService;

  @Test
  void enqueueStampsManualTriggerType() {
    Workflow workflow = saveWorkflow(newUser("trigger@example.com"), "{\"nodes\":[],\"edges\":[]}");

    WorkflowRun run = executionService.enqueue(workflow.getId());

    assertThat(run.getTriggerType()).isEqualTo(TriggerType.MANUAL);
  }

  @Test
  void invalidGraphJsonMarksRunFailedWithValidationCause() {
    // graph_json is a native JSON column (@JdbcTypeCode(SqlTypes.JSON) on Workflow), so H2 (like
    // MySQL) rejects syntactically-invalid JSON at INSERT time -- a bare "not-json" string can
    // never reach parseGraph(). Use JSON that is syntactically valid (so it persists) but
    // structurally wrong (a String where WorkflowGraph.nodes expects a List), so
    // parseGraph()'s Jackson deserialization still fails and wraps into IllegalArgumentException.
    Workflow workflow =
        saveWorkflow(newUser("badgraph@example.com"), "{\"nodes\":\"not-a-list\",\"edges\":[]}");

    WorkflowRun run = executionService.enqueue(workflow.getId());
    WorkflowRun executed = executionService.execute(run);

    assertThat(executed.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
    assertThat(executed.getErrorCause()).isEqualTo(ErrorCause.VALIDATION);
  }

  @Test
  void nodeConfigFailureMarksRunFailedWithOtherCause() {
    // A single OUTPUT node with no config: EmailOutputNodeExecutor returns
    // NodeExecutionResult.failure("Email node requires config 'to'") without throwing, exercising
    // the node-failure branch (not the outer catch) — the common real-world path.
    Workflow workflow =
        saveWorkflow(
            newUser("emailcfg@example.com"),
            "{\"nodes\":[{\"id\":\"e\",\"type\":\"OUTPUT\",\"data\":{}}],\"edges\":[]}");

    WorkflowRun run = executionService.enqueue(workflow.getId());
    WorkflowRun executed = executionService.execute(run);

    assertThat(executed.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
    assertThat(executed.getErrorCause()).isEqualTo(ErrorCause.OTHER);
  }

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

  private Workflow saveWorkflow(User user, String graphJson) {
    return workflowRepository.save(
        Workflow.builder().user(user).name("wf").graphJson(graphJson).build());
  }
}
