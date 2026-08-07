package com.flowmatic.auth.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import org.junit.jupiter.api.Test;

class StripePlanPropertiesTest {

  private final StripePlanProperties properties =
      new StripePlanProperties("price_essentials", "price_pro", "price_enterprise");

  @Test
  void priceIdForPlanReturnsTheConfiguredId() {
    assertThat(properties.priceIdForPlan(SubscriptionPlan.ESSENTIALS))
        .isEqualTo("price_essentials");
    assertThat(properties.priceIdForPlan(SubscriptionPlan.PRO)).isEqualTo("price_pro");
    assertThat(properties.priceIdForPlan(SubscriptionPlan.ENTERPRISE))
        .isEqualTo("price_enterprise");
  }

  @Test
  void planForPriceIdResolvesTheReverseLookup() {
    assertThat(properties.planForPriceId("price_pro")).contains(SubscriptionPlan.PRO);
  }

  @Test
  void planForPriceIdIsEmptyForAnUnrecognizedId() {
    assertThat(properties.planForPriceId("price_unknown")).isEmpty();
  }
}
