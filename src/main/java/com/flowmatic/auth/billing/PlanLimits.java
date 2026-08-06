package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a paid plan to its configured lifetime workflow-run limit. */
@Component
public class PlanLimits {

  private final int essentialsLimit;
  private final int proLimit;

  public PlanLimits(
      @Value("${app.workflow.plan-limits.essentials:100}") int essentialsLimit,
      @Value("${app.workflow.plan-limits.pro:1000}") int proLimit) {
    this.essentialsLimit = essentialsLimit;
    this.proLimit = proLimit;
  }

  /** ESSENTIALS/PRO only — ENTERPRISE has no numeric limit, it's a full exemption. */
  public int forPlan(SubscriptionPlan plan) {
    return switch (plan) {
      case ESSENTIALS -> essentialsLimit;
      case PRO -> proLimit;
      case ENTERPRISE ->
          throw new IllegalArgumentException("ENTERPRISE has no numeric limit; it is exempt");
    };
  }
}
