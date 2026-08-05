package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces each user's lifetime cap on workflow runs. */
@Service
public class WorkflowRunQuotaService {

  private final UserRepository userRepository;

  @Value("${app.workflow.lifetime-run-limit:10}")
  private int lifetimeRunLimit;

  public WorkflowRunQuotaService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Increments the user's lifetime run count, or throws 402 if they've already hit the cap.
   *
   * @throws ResponseStatusException 402 if the user is at their lifetime limit and not exempt
   */
  public void enforceQuota(Long userId) {
    if (isExempt(loadUser(userId))) {
      return;
    }
    int updated = userRepository.incrementRunCountIfUnderLimit(userId, lifetimeRunLimit);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "You've reached your limit of " + lifetimeRunLimit + " workflow runs. Subscribe to continue.");
    }
  }

  /** The current user's lifetime run usage, for display before they hit the wall. */
  public WorkflowRunUsageDTO usage(Long userId) {
    User user = loadUser(userId);
    if (isExempt(user)) {
      return new WorkflowRunUsageDTO(0, null, null, true);
    }
    int used = user.getWorkflowRunCount() == null ? 0 : user.getWorkflowRunCount();
    return new WorkflowRunUsageDTO(used, lifetimeRunLimit, lifetimeRunLimit - used, false);
  }

  private User loadUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
  }

  /** The one extension point for a later subscription check, e.g. {@code || hasActivePlan(user)}. */
  private boolean isExempt(User user) {
    return user.getRole() == Role.ADMIN;
  }
}
