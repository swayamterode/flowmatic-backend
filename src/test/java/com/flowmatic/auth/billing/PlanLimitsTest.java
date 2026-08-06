package com.flowmatic.auth.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import org.junit.jupiter.api.Test;

class PlanLimitsTest {

  private final PlanLimits planLimits = new PlanLimits(100, 1000);

  @Test
  void essentialsUsesTheConfiguredLimit() {
    assertThat(planLimits.forPlan(SubscriptionPlan.ESSENTIALS)).isEqualTo(100);
  }

  @Test
  void proUsesTheConfiguredLimit() {
    assertThat(planLimits.forPlan(SubscriptionPlan.PRO)).isEqualTo(1000);
  }

  @Test
  void enterpriseHasNoNumericLimit() {
    assertThatThrownBy(() -> planLimits.forPlan(SubscriptionPlan.ENTERPRISE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
