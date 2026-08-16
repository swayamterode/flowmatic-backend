package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.billing.entity.Subscription;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.flowmatic.auth.billing.repository.SubscriptionRepository;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import com.flowmatic.auth.workflow.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Each user gets a lifetime cap of 10 workflow runs, enforced the moment a run is enqueued. ADMIN
 * users are exempt, and the cap can never be reset by deleting workflow history.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunQuotaIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired WorkflowRunRepository workflowRunRepository;
  @Autowired WorkflowExecutionService executionService;
  @Autowired WorkflowRunQuotaService quotaService;
  @Autowired SubscriptionRepository subscriptionRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private User newUser(String email, Role role) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(role)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String name) {
    Map<String, Object> graph =
        Map.of(
            "nodes",
            List.of(Map.of("id", "t", "type", "TRIGGER", "data", Map.of())),
            "edges",
            List.of());
    try {
      return workflowRepository.save(
          Workflow.builder()
              .user(user)
              .name(name)
              .graphJson(objectMapper.writeValueAsString(graph))
              .build());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void giveSubscription(User user, SubscriptionPlan plan, SubscriptionStatus status) {
    subscriptionRepository.save(
        Subscription.builder()
            .user(user)
            .stripeCustomerId("cus_test")
            .stripeSubscriptionId("sub_test_" + user.getId())
            .plan(plan)
            .status(status)
            .currentPeriodEnd(Instant.now().plusSeconds(3600))
            .build());
  }

  @Test
  void tenthEnqueueSucceedsEleventhIsRejectedWith402() {
    User user = newUser("cap@example.com", Role.USER);
    Workflow workflow = saveWorkflow(user, "capped");

    for (int i = 0; i < 10; i++) {
      WorkflowRun run = executionService.enqueue(workflow.getId());
      assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
    }

    assertThatThrownBy(() -> executionService.enqueue(workflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);

    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .hasSize(10);
  }

  @Test
  void adminBypassesTheCapEntirely() {
    User admin = newUser("admin@example.com", Role.ADMIN);
    Workflow workflow = saveWorkflow(admin, "admin-workflow");

    for (int i = 0; i < 15; i++) {
      WorkflowRun run = executionService.enqueue(workflow.getId());
      assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
    }

    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .hasSize(15);
  }

  @Test
  void capIsPerUserAcrossMultipleWorkflows() {
    User user = newUser("multi@example.com", Role.USER);
    Workflow workflowA = saveWorkflow(user, "a");
    Workflow workflowB = saveWorkflow(user, "b");

    for (int i = 0; i < 5; i++) {
      executionService.enqueue(workflowA.getId());
    }
    for (int i = 0; i < 5; i++) {
      executionService.enqueue(workflowB.getId());
    }

    assertThatThrownBy(() -> executionService.enqueue(workflowA.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    assertThatThrownBy(() -> executionService.enqueue(workflowB.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
  }

  @Test
  void deletingAWorkflowDoesNotResetTheUsersLifetimeCount() {
    User user = newUser("delete-loophole@example.com", Role.USER);
    Workflow workflowA = saveWorkflow(user, "a");

    for (int i = 0; i < 10; i++) {
      executionService.enqueue(workflowA.getId());
    }
    while (executionService.runNextPending()) {
      // drain all 10 runs to SUCCESS so deleteWithHistory isn't blocked
    }

    executionService.deleteWithHistory(workflowA.getId());
    Workflow workflowB = saveWorkflow(user, "b");

    assertThatThrownBy(() -> executionService.enqueue(workflowB.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
  }

  @Test
  void usageReflectsUsedLimitAndRemaining() {
    User user = newUser("usage@example.com", Role.USER);
    Workflow workflow = saveWorkflow(user, "usage-wf");

    WorkflowRunUsageDTO fresh = quotaService.usage(user.getId());
    assertThat(fresh.used()).isEqualTo(0);
    assertThat(fresh.limit()).isEqualTo(10);
    assertThat(fresh.remaining()).isEqualTo(10);
    assertThat(fresh.unlimited()).isFalse();

    for (int i = 0; i < 3; i++) {
      executionService.enqueue(workflow.getId());
    }
    WorkflowRunUsageDTO afterThree = quotaService.usage(user.getId());
    assertThat(afterThree.used()).isEqualTo(3);
    assertThat(afterThree.remaining()).isEqualTo(7);
  }

  @Test
  void usageIsUnlimitedForAdmin() {
    User admin = newUser("usage-admin@example.com", Role.ADMIN);

    WorkflowRunUsageDTO usage = quotaService.usage(admin.getId());

    assertThat(usage.unlimited()).isTrue();
    assertThat(usage.limit()).isNull();
    assertThat(usage.remaining()).isNull();
  }

  @Test
  void essentialsSubscriberIsCappedAtTheEssentialsLimitNotTheFreeLimit() {
    User user = newUser("essentials@example.com", Role.USER);
    giveSubscription(user, SubscriptionPlan.ESSENTIALS, SubscriptionStatus.ACTIVE);
    Workflow workflow = saveWorkflow(user, "essentials-wf");

    for (int i = 0; i < 100; i++) {
      WorkflowRun run = executionService.enqueue(workflow.getId());
      assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
    }

    assertThatThrownBy(() -> executionService.enqueue(workflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
  }

  @Test
  void activeEnterpriseSubscriberIsExemptLikeAdmin() {
    User user = newUser("enterprise@example.com", Role.USER);
    giveSubscription(user, SubscriptionPlan.ENTERPRISE, SubscriptionStatus.ACTIVE);
    Workflow workflow = saveWorkflow(user, "enterprise-wf");

    for (int i = 0; i < 15; i++) {
      WorkflowRun run = executionService.enqueue(workflow.getId());
      assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
    }

    assertThat(workflowRunRepository.findByWorkflow_IdOrderByStartedAtDesc(workflow.getId()))
        .hasSize(15);
  }

  @Test
  void canceledEssentialsSubscriberWhoUsedMoreThanTheFreeLimitIsImmediatelyBlocked() {
    User user = newUser("canceled-essentials@example.com", Role.USER);
    giveSubscription(user, SubscriptionPlan.ESSENTIALS, SubscriptionStatus.ACTIVE);
    Workflow workflow = saveWorkflow(user, "canceled-essentials-wf");

    for (int i = 0; i < 20; i++) {
      executionService.enqueue(workflow.getId());
    }

    subscriptionRepository
        .findByUserId(user.getId())
        .ifPresent(
            s -> {
              s.setStatus(SubscriptionStatus.CANCELED);
              subscriptionRepository.save(s);
            });

    assertThatThrownBy(() -> executionService.enqueue(workflow.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
  }

  @Test
  void usageReportsTheActivePlanName() {
    User user = newUser("usage-plan@example.com", Role.USER);
    giveSubscription(user, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE);

    WorkflowRunUsageDTO usage = quotaService.usage(user.getId());

    assertThat(usage.plan()).isEqualTo("PRO");
    assertThat(usage.limit()).isEqualTo(1000);
  }
}
