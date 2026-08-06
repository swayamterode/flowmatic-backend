package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.billing.PlanLimits;
import com.flowmatic.auth.billing.SubscriptionService;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces each user's lifetime cap on workflow runs, raised or removed by an active plan. */
@Service
public class WorkflowRunQuotaService {

  private final UserRepository userRepository;
  private final SubscriptionService subscriptionService;
  private final PlanLimits planLimits;

  @Value("${app.workflow.lifetime-run-limit:10}")
  private int lifetimeRunLimit;

  public WorkflowRunQuotaService(
      UserRepository userRepository,
      SubscriptionService subscriptionService,
      PlanLimits planLimits) {
    this.userRepository = userRepository;
    this.subscriptionService = subscriptionService;
    this.planLimits = planLimits;
  }

  /**
   * Increments the user's lifetime run count, or throws 402 if they've already hit the cap.
   *
   * @throws ResponseStatusException 402 if the user is at their lifetime limit and not exempt
   */
  public void enforceQuota(Long userId) {
    User user = loadUser(userId);
    if (isExempt(user)) {
      return;
    }
    int limit = effectiveLimit(user);
    int updated = userRepository.incrementRunCountIfUnderLimit(userId, limit);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "You've reached your limit of " + limit + " workflow runs. Subscribe to continue.");
    }
  }

  /** The current user's lifetime run usage, for display before they hit the wall. */
  public WorkflowRunUsageDTO usage(Long userId) {
    User user = loadUser(userId);
    Optional<SubscriptionPlan> plan = subscriptionService.activePlan(user.getId());
    String planName = plan.map(SubscriptionPlan::name).orElse(null);
    if (isExempt(user)) {
      return new WorkflowRunUsageDTO(0, null, null, true, planName);
    }
    int used = user.getWorkflowRunCount() == null ? 0 : user.getWorkflowRunCount();
    int limit = effectiveLimit(user);
    return new WorkflowRunUsageDTO(used, limit, limit - used, false, planName);
  }

  private User loadUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
  }

  private boolean isExempt(User user) {
    return user.getRole() == Role.ADMIN
        || subscriptionService
            .activePlan(user.getId())
            .filter(plan -> plan == SubscriptionPlan.ENTERPRISE)
            .isPresent();
  }

  private int effectiveLimit(User user) {
    return subscriptionService
        .activePlan(user.getId())
        .map(planLimits::forPlan)
        .orElse(lifetimeRunLimit);
  }
}
