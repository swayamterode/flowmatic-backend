package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Builds a Stripe Checkout Session (subscription mode) for a user's chosen plan. */
@Service
public class StripeCheckoutService {

  private final StripeClient stripeClient;
  private final SubscriptionService subscriptionService;
  private final StripePlanProperties planProperties;
  private final String successUrl;
  private final String cancelUrl;

  public StripeCheckoutService(
      StripeClient stripeClient,
      SubscriptionService subscriptionService,
      StripePlanProperties planProperties,
      @Value("${app.frontend.success-url}") String successUrl,
      @Value("${app.frontend.cancel-url}") String cancelUrl) {
    this.stripeClient = stripeClient;
    this.subscriptionService = subscriptionService;
    this.planProperties = planProperties;
    this.successUrl = successUrl;
    this.cancelUrl = cancelUrl;
  }

  /**
   * @throws ResponseStatusException 409 if the user already has an active subscription
   * @throws StripeException if Stripe's API rejects or fails the request
   */
  public String createCheckoutSession(Long userId, SubscriptionPlan plan) throws StripeException {
    if (subscriptionService.hasActiveSubscription(userId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "You already have an active subscription");
    }

    SessionCreateParams.Builder params =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setPrice(planProperties.priceIdForPlan(plan))
                    .setQuantity(1L)
                    .build())
            .setClientReferenceId(String.valueOf(userId))
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl);
    subscriptionService.stripeCustomerId(userId).ifPresent(params::setCustomer);

    Session session = stripeClient.v1().checkout().sessions().create(params.build());
    return session.getUrl();
  }
}
