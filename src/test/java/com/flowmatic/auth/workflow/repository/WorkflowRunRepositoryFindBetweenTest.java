package com.flowmatic.auth.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunRepositoryFindBetweenTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void findsOnlyTheGivenUsersRunsWithinTheHalfOpenWindow() {
    User owner = saveUser("between-owner@example.com");
    User other = saveUser("between-other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);

    Instant from = Instant.parse("2026-08-01T00:00:00Z");
    Instant to = Instant.parse("2026-08-02T00:00:00Z");
    saveRun(ownerWorkflow, from); // at lower boundary: included
    saveRun(ownerWorkflow, from.plusSeconds(43200)); // mid-window: included
    saveRun(ownerWorkflow, to.minusSeconds(1)); // just under upper boundary: included
    saveRun(ownerWorkflow, from.minusSeconds(1)); // before window: excluded
    saveRun(ownerWorkflow, to); // at upper boundary (exclusive): excluded
    saveRun(otherWorkflow, from.plusSeconds(3600)); // other user, in-window: excluded
    workflowRunRepository.save(
        WorkflowRun.builder().workflow(ownerWorkflow).status(WorkflowRunStatus.PENDING).build());

    var found =
        workflowRunRepository
            .findByWorkflow_User_IdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                owner.getId(), from, to);

    assertThat(found).hasSize(3);
  }

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
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
