package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Verifies and dispatches Stripe webhook events, the source of truth for subscription state. */
@Service
public class StripeWebhookService {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

  private final StripeClient stripeClient;
  private final SubscriptionService subscriptionService;
  private final StripePlanProperties planProperties;
  private final String webhookSecret;

  public StripeWebhookService(
      StripeClient stripeClient,
      SubscriptionService subscriptionService,
      StripePlanProperties planProperties,
      @Value("${app.stripe.webhook-secret:}") String webhookSecret) {
    this.stripeClient = stripeClient;
    this.subscriptionService = subscriptionService;
    this.planProperties = planProperties;
    this.webhookSecret = webhookSecret;
  }

  public void handleWebhook(String payload, String sigHeader)
      throws SignatureVerificationException, StripeException {
    Event event = stripeClient.constructEvent(payload, sigHeader, webhookSecret);
    switch (event.getType()) {
      case "checkout.session.completed" -> handleCheckoutCompleted(event);
      case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
      case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
      default -> log.info("Ignoring unhandled Stripe event type {}", event.getType());
    }
  }

  private void handleCheckoutCompleted(Event event) throws StripeException {
    Session session =
        (Session)
            event
                .getDataObjectDeserializer()
                .getObject()
                .orElseThrow(
                    () -> new IllegalStateException("Unable to deserialize " + event.getId()));
    Long userId = Long.valueOf(session.getClientReferenceId());
    Subscription subscription =
        stripeClient.v1().subscriptions().retrieve(session.getSubscription());

    Optional<SubscriptionPlan> plan = planFor(subscription);
    if (plan.isEmpty()) {
      log.warn(
          "checkout.session.completed for subscription {} has an unrecognized price id",
          subscription.getId());
      return;
    }
    subscriptionService.upsertFromCheckout(
        userId,
        plan.get(),
        session.getCustomer(),
        subscription.getId(),
        currentPeriodEnd(subscription));
  }

  private void handleSubscriptionUpdated(Event event) {
    Subscription subscription = subscriptionObject(event);
    Optional<SubscriptionPlan> plan = planFor(subscription);
    if (plan.isEmpty()) {
      log.warn(
          "customer.subscription.updated for {} has an unrecognized price id",
          subscription.getId());
      return;
    }
    subscriptionService.updateFromStripeSubscription(
        subscription.getId(), statusFor(subscription), plan.get(), currentPeriodEnd(subscription));
  }

  private void handleSubscriptionDeleted(Event event) {
    subscriptionService.markCanceled(subscriptionObject(event).getId());
  }

  private static Subscription subscriptionObject(Event event) {
    return (Subscription)
        event
            .getDataObjectDeserializer()
            .getObject()
            .orElseThrow(() -> new IllegalStateException("Unable to deserialize " + event.getId()));
  }

  private Optional<SubscriptionPlan> planFor(Subscription subscription) {
    String priceId = subscription.getItems().getData().get(0).getPrice().getId();
    return planProperties.planForPriceId(priceId);
  }

  private static Instant currentPeriodEnd(Subscription subscription) {
    return Instant.ofEpochSecond(subscription.getItems().getData().get(0).getCurrentPeriodEnd());
  }

  private static SubscriptionStatus statusFor(Subscription subscription) {
    return switch (subscription.getStatus()) {
      case "active" -> SubscriptionStatus.ACTIVE;
      case "canceled" -> SubscriptionStatus.CANCELED;
      default -> SubscriptionStatus.PAST_DUE;
    };
  }
}
