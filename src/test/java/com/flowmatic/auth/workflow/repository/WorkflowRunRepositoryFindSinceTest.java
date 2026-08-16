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
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunRepositoryFindSinceTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;

  @Test
  void findsOnlyTheGivenUsersRunsAtOrAfterSince() {
    User owner = saveUser("owner@example.com");
    User other = saveUser("other@example.com");
    Workflow ownerWorkflow = saveWorkflow(owner);
    Workflow otherWorkflow = saveWorkflow(other);

    Instant since = Instant.parse("2026-08-01T00:00:00Z");
    saveRun(ownerWorkflow, since); // exactly at the boundary: included
    saveRun(ownerWorkflow, since.plus(1, ChronoUnit.DAYS)); // after: included
    saveRun(ownerWorkflow, since.minus(1, ChronoUnit.SECONDS)); // before: excluded
    saveRun(otherWorkflow, since.plus(1, ChronoUnit.DAYS)); // other user: excluded
    // PENDING run with null startedAt: excluded (never reached execute())
    workflowRunRepository.save(
        WorkflowRun.builder().workflow(ownerWorkflow).status(WorkflowRunStatus.PENDING).build());

    var found =
        workflowRunRepository.findByWorkflow_User_IdAndStartedAtGreaterThanEqual(
            owner.getId(), since);

    assertThat(found).hasSize(2);
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
