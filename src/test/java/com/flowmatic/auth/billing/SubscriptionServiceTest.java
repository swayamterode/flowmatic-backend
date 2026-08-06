package com.flowmatic.auth.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.billing.entity.Subscription;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.flowmatic.auth.billing.repository.SubscriptionRepository;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubscriptionServiceTest {

  private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final SubscriptionService service =
      new SubscriptionService(subscriptionRepository, userRepository);

  private Subscription subscription(SubscriptionStatus status, SubscriptionPlan plan) {
    return Subscription.builder().status(status).plan(plan).stripeCustomerId("cus_1").build();
  }

  @Test
  void activePlanReturnsThePlanOnlyWhenStatusIsActive() {
    when(subscriptionRepository.findByUserId(1L))
        .thenReturn(Optional.of(subscription(SubscriptionStatus.ACTIVE, SubscriptionPlan.PRO)));

    assertThat(service.activePlan(1L)).contains(SubscriptionPlan.PRO);
  }

  @Test
  void activePlanIsEmptyWhenCanceled() {
    when(subscriptionRepository.findByUserId(1L))
        .thenReturn(
            Optional.of(subscription(SubscriptionStatus.CANCELED, SubscriptionPlan.ESSENTIALS)));

    assertThat(service.activePlan(1L)).isEmpty();
  }

  @Test
  void activePlanIsEmptyWhenNoSubscriptionExists() {
    when(subscriptionRepository.findByUserId(1L)).thenReturn(Optional.empty());

    assertThat(service.activePlan(1L)).isEmpty();
  }

  @Test
  void hasActiveSubscriptionIsFalseForPastDue() {
    when(subscriptionRepository.findByUserId(1L))
        .thenReturn(Optional.of(subscription(SubscriptionStatus.PAST_DUE, SubscriptionPlan.PRO)));

    assertThat(service.hasActiveSubscription(1L)).isFalse();
  }

  @Test
  void stripeCustomerIdReturnsTheStoredIdWhenARowExists() {
    when(subscriptionRepository.findByUserId(1L))
        .thenReturn(Optional.of(subscription(SubscriptionStatus.CANCELED, SubscriptionPlan.PRO)));

    assertThat(service.stripeCustomerId(1L)).contains("cus_1");
  }

  @Test
  void upsertFromCheckoutCreatesANewRowForAFirstTimeSubscriber() {
    when(subscriptionRepository.findByUserId(1L)).thenReturn(Optional.empty());
    User user = User.builder().id(1L).build();
    when(userRepository.getReferenceById(1L)).thenReturn(user);
    Instant periodEnd = Instant.now();

    service.upsertFromCheckout(1L, SubscriptionPlan.ESSENTIALS, "cus_9", "sub_9", periodEnd);

    verify(subscriptionRepository)
        .save(
            argThatSubscription(
                s ->
                    s.getUser() == user
                        && s.getPlan() == SubscriptionPlan.ESSENTIALS
                        && s.getStatus() == SubscriptionStatus.ACTIVE
                        && s.getStripeCustomerId().equals("cus_9")
                        && s.getStripeSubscriptionId().equals("sub_9")
                        && s.getCurrentPeriodEnd().equals(periodEnd)));
  }

  @Test
  void upsertFromCheckoutUpdatesAnExistingRowOnResubscribe() {
    Subscription existing =
        subscription(SubscriptionStatus.CANCELED, SubscriptionPlan.ESSENTIALS);
    when(subscriptionRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
    Instant periodEnd = Instant.now();

    service.upsertFromCheckout(1L, SubscriptionPlan.PRO, "cus_1", "sub_new", periodEnd);

    assertThat(existing.getPlan()).isEqualTo(SubscriptionPlan.PRO);
    assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(existing.getStripeSubscriptionId()).isEqualTo("sub_new");
    verify(subscriptionRepository).save(existing);
    verify(userRepository, never()).getReferenceById(any());
  }

  @Test
  void updateFromStripeSubscriptionUpdatesTheMatchingRow() {
    Subscription existing = subscription(SubscriptionStatus.ACTIVE, SubscriptionPlan.ESSENTIALS);
    when(subscriptionRepository.findByStripeSubscriptionId("sub_9")).thenReturn(Optional.of(existing));
    Instant periodEnd = Instant.now();

    service.updateFromStripeSubscription(
        "sub_9", SubscriptionStatus.PAST_DUE, SubscriptionPlan.PRO, periodEnd);

    assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(existing.getPlan()).isEqualTo(SubscriptionPlan.PRO);
    assertThat(existing.getCurrentPeriodEnd()).isEqualTo(periodEnd);
    verify(subscriptionRepository).save(existing);
  }

  @Test
  void updateFromStripeSubscriptionNoOpsForAnUnrecognizedSubscriptionId() {
    when(subscriptionRepository.findByStripeSubscriptionId("unknown")).thenReturn(Optional.empty());

    service.updateFromStripeSubscription(
        "unknown", SubscriptionStatus.ACTIVE, SubscriptionPlan.PRO, Instant.now());

    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  void markCanceledSetsStatusToCanceled() {
    Subscription existing = subscription(SubscriptionStatus.ACTIVE, SubscriptionPlan.PRO);
    when(subscriptionRepository.findByStripeSubscriptionId("sub_9")).thenReturn(Optional.of(existing));

    service.markCanceled("sub_9");

    assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    verify(subscriptionRepository).save(existing);
  }

  @Test
  void markCanceledNoOpsForAnUnrecognizedSubscriptionId() {
    when(subscriptionRepository.findByStripeSubscriptionId("unknown")).thenReturn(Optional.empty());

    service.markCanceled("unknown");

    verify(subscriptionRepository, never()).save(any());
  }

  private static Subscription argThatSubscription(java.util.function.Predicate<Subscription> predicate) {
    return org.mockito.ArgumentMatchers.argThat(predicate::test);
  }
}
