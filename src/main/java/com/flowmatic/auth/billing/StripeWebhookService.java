package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
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
    Session session = (Session) dataObject(event);
    if (!"subscription".equals(session.getMode())
        || session.getClientReferenceId() == null
        || session.getSubscription() == null) {
      log.warn(
          "Ignoring checkout.session.completed for session {} — not a subscription checkout"
              + " created by this app (mode={}, clientReferenceId={}, subscription={})",
          session.getId(),
          session.getMode(),
          session.getClientReferenceId(),
          session.getSubscription());
      return;
    }
    Long userId;
    try {
      userId = Long.valueOf(session.getClientReferenceId());
    } catch (NumberFormatException e) {
      log.warn(
          "Ignoring checkout.session.completed for session {} — clientReferenceId {} is not a"
              + " valid user id",
          session.getId(),
          session.getClientReferenceId());
      return;
    }
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
    return (Subscription) dataObject(event);
  }

  /**
   * {@link EventDataObjectDeserializer#getObject()} returns empty whenever the event's {@code
   * api_version} doesn't exactly match the SDK's compiled-in version — which is the normal case in
   * production, since Stripe stamps events with the account's configured API version, not the
   * SDK's. Fall back to {@code deserializeUnsafe()}, Stripe's documented pattern for this case.
   */
  private static StripeObject dataObject(Event event) {
    EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
    return deserializer
        .getObject()
        .orElseGet(
            () -> {
              try {
                return deserializer.deserializeUnsafe();
              } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException("Unable to deserialize " + event.getId(), e);
              }
            });
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
