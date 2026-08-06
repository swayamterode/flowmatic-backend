package com.flowmatic.auth.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.stripe.StripeClient;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StripeCheckoutServiceTest {

  private final StripeClient stripeClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final StripePlanProperties planProperties =
      new StripePlanProperties("price_essentials", "price_pro", "price_enterprise");
  private final StripeCheckoutService service =
      new StripeCheckoutService(
          stripeClient,
          subscriptionService,
          planProperties,
          "https://example.com/success",
          "https://example.com/cancel");

  @Test
  void createsASubscriptionModeSessionAndReturnsItsUrl() throws Exception {
    when(subscriptionService.hasActiveSubscription(1L)).thenReturn(false);
    when(subscriptionService.stripeCustomerId(1L)).thenReturn(Optional.empty());
    Session session = mock(Session.class);
    when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test_123");
    when(stripeClient.v1().checkout().sessions().create(any(SessionCreateParams.class)))
        .thenReturn(session);

    String url = service.createCheckoutSession(1L, SubscriptionPlan.PRO);

    assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_123");
  }

  @Test
  void reusesAnExistingStripeCustomerIdWhenOneIsOnFile() throws Exception {
    when(subscriptionService.hasActiveSubscription(1L)).thenReturn(false);
    when(subscriptionService.stripeCustomerId(1L)).thenReturn(Optional.of("cus_existing"));
    Session session = mock(Session.class);
    when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test_456");
    when(stripeClient.v1().checkout().sessions().create(any(SessionCreateParams.class)))
        .thenReturn(session);

    service.createCheckoutSession(1L, SubscriptionPlan.ESSENTIALS);

    verify(stripeClient.v1().checkout().sessions())
        .create(
            org.mockito.ArgumentMatchers.argThat(
                (SessionCreateParams params) -> "cus_existing".equals(params.getCustomer())));
  }

  @Test
  void rejectsASecondCheckoutWhenAlreadySubscribed() {
    when(subscriptionService.hasActiveSubscription(1L)).thenReturn(true);

    assertThatThrownBy(() -> service.createCheckoutSession(1L, SubscriptionPlan.PRO))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }
}
