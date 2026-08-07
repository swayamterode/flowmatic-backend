package com.flowmatic.auth.billing;

import com.flowmatic.auth.billing.entity.Subscription;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.flowmatic.auth.billing.repository.SubscriptionRepository;
import com.flowmatic.auth.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes each user's {@link Subscription}, kept in sync via Stripe webhooks. */
@Service
public class SubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

  private final SubscriptionRepository subscriptionRepository;
  private final UserRepository userRepository;

  public SubscriptionService(
      SubscriptionRepository subscriptionRepository, UserRepository userRepository) {
    this.subscriptionRepository = subscriptionRepository;
    this.userRepository = userRepository;
  }

  public Optional<SubscriptionPlan> activePlan(Long userId) {
    return subscriptionRepository
        .findByUserId(userId)
        .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE)
        .map(Subscription::getPlan);
  }

  public boolean hasActiveSubscription(Long userId) {
    return activePlan(userId).isPresent();
  }

  public Optional<String> stripeCustomerId(Long userId) {
    return subscriptionRepository.findByUserId(userId).map(Subscription::getStripeCustomerId);
  }

  /** Called after {@code checkout.session.completed}: creates or reactivates the user's row. */
  @Transactional
  public void upsertFromCheckout(
      Long userId,
      SubscriptionPlan plan,
      String stripeCustomerId,
      String stripeSubscriptionId,
      Instant currentPeriodEnd) {
    Subscription subscription =
        subscriptionRepository
            .findByUserId(userId)
            .orElseGet(
                () -> Subscription.builder().user(userRepository.getReferenceById(userId)).build());
    subscription.setPlan(plan);
    subscription.setStatus(SubscriptionStatus.ACTIVE);
    subscription.setStripeCustomerId(stripeCustomerId);
    subscription.setStripeSubscriptionId(stripeSubscriptionId);
    subscription.setCurrentPeriodEnd(currentPeriodEnd);
    subscriptionRepository.save(subscription);
  }

  /** Called on {@code customer.subscription.updated} (renewals, {@code active -> past_due}). */
  @Transactional
  public void updateFromStripeSubscription(
      String stripeSubscriptionId,
      SubscriptionStatus status,
      SubscriptionPlan plan,
      Instant currentPeriodEnd) {
    subscriptionRepository
        .findByStripeSubscriptionId(stripeSubscriptionId)
        .ifPresentOrElse(
            subscription -> {
              subscription.setStatus(status);
              subscription.setPlan(plan);
              subscription.setCurrentPeriodEnd(currentPeriodEnd);
              subscriptionRepository.save(subscription);
            },
            () ->
                log.warn(
                    "customer.subscription.updated for unrecognized stripeSubscriptionId {}",
                    stripeSubscriptionId));
  }

  /** Called on {@code customer.subscription.deleted}. */
  @Transactional
  public void markCanceled(String stripeSubscriptionId) {
    subscriptionRepository
        .findByStripeSubscriptionId(stripeSubscriptionId)
        .ifPresentOrElse(
            subscription -> {
              subscription.setStatus(SubscriptionStatus.CANCELED);
              subscriptionRepository.save(subscription);
            },
            () ->
                log.warn(
                    "customer.subscription.deleted for unrecognized stripeSubscriptionId {}",
                    stripeSubscriptionId));
  }
}
