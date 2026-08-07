package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Maps each {@link SubscriptionPlan} to its configured Stripe Price ID, and back. */
@Component
public class StripePlanProperties {

  private final Map<SubscriptionPlan, String> priceIdsByPlan;

  public StripePlanProperties(
      @Value("${app.stripe.price-id.essentials:}") String essentials,
      @Value("${app.stripe.price-id.pro:}") String pro,
      @Value("${app.stripe.price-id.enterprise:}") String enterprise) {
    this.priceIdsByPlan =
        Map.of(
            SubscriptionPlan.ESSENTIALS, essentials,
            SubscriptionPlan.PRO, pro,
            SubscriptionPlan.ENTERPRISE, enterprise);
  }

  public String priceIdForPlan(SubscriptionPlan plan) {
    return priceIdsByPlan.get(plan);
  }

  public Optional<SubscriptionPlan> planForPriceId(String priceId) {
    return priceIdsByPlan.entrySet().stream()
        .filter(entry -> entry.getValue().equals(priceId))
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
