package com.flowmatic.auth.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StripeWebhookServiceTest {

  private final StripeClient stripeClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final StripePlanProperties planProperties =
      new StripePlanProperties("price_essentials", "price_pro", "price_enterprise");
  private final StripeWebhookService service =
      new StripeWebhookService(stripeClient, subscriptionService, planProperties, "whsec_test");

  private static final String SUBSCRIPTION_JSON =
      """
      {
        "id": "sub_123",
        "object": "subscription",
        "customer": "cus_123",
        "status": "active",
        "items": {
          "object": "list",
          "data": [
            {
              "id": "si_123",
              "object": "subscription_item",
              "current_period_end": 1700000000,
              "current_period_start": 1697000000,
              "price": { "id": "price_pro", "object": "price" }
            }
          ]
        }
      }
      """;

  private Event checkoutCompletedEvent() {
    String json =
        """
        {
          "id": "evt_1",
          "object": "event",
          "api_version": "2026-07-29.dahlia",
          "type": "checkout.session.completed",
          "data": {
            "object": {
              "id": "cs_123",
              "object": "checkout.session",
              "mode": "subscription",
              "client_reference_id": "42",
              "customer": "cus_123",
              "subscription": "sub_123"
            }
          }
        }
        """;
    return ApiResource.GSON.fromJson(json, Event.class);
  }

  private Event subscriptionEvent(String type) {
    return subscriptionEvent(type, "2026-07-29.dahlia");
  }

  private Event subscriptionEvent(String type, String apiVersion) {
    String json =
        """
        {
          "id": "evt_2",
          "object": "event",
          "api_version": "%s",
          "type": "%s",
          "data": { "object": %s }
        }
        """
            .formatted(apiVersion, type, SUBSCRIPTION_JSON);
    return ApiResource.GSON.fromJson(json, Event.class);
  }

  @Test
  void rejectsATamperedPayload() throws Exception {
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenThrow(new SignatureVerificationException("bad signature", "t=1,v1=bad"));

    org.junit.jupiter.api.Assertions.assertThrows(
        SignatureVerificationException.class, () -> service.handleWebhook("{}", "t=1,v1=bad"));
  }

  @Test
  void checkoutSessionCompletedUpsertsTheSubscription() throws Exception {
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(checkoutCompletedEvent());
    com.stripe.model.Subscription stripeSubscription =
        ApiResource.GSON.fromJson(SUBSCRIPTION_JSON, com.stripe.model.Subscription.class);
    when(stripeClient.v1().subscriptions().retrieve("sub_123")).thenReturn(stripeSubscription);

    service.handleWebhook("payload", "sig");

    verify(subscriptionService)
        .upsertFromCheckout(
            42L, SubscriptionPlan.PRO, "cus_123", "sub_123", Instant.ofEpochSecond(1700000000));
  }

  @Test
  void checkoutSessionCompletedIgnoresANonSubscriptionCheckoutSession() throws Exception {
    // checkout.session.completed fires for every Checkout Session on the whole Stripe account —
    // e.g. a Payment Link, a one-time payment, or `stripe trigger checkout.session.completed` —
    // not just the subscription-mode sessions this app's own StripeCheckoutService creates.
    String json =
        """
        {
          "id": "evt_4",
          "object": "event",
          "api_version": "2026-07-29.dahlia",
          "type": "checkout.session.completed",
          "data": {
            "object": {
              "id": "cs_456",
              "object": "checkout.session",
              "mode": "payment",
              "client_reference_id": null,
              "customer": "cus_123",
              "subscription": null
            }
          }
        }
        """;
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(ApiResource.GSON.fromJson(json, Event.class));

    service.handleWebhook("payload", "sig");

    verify(subscriptionService, never()).upsertFromCheckout(any(), any(), any(), any(), any());
  }

  @Test
  void checkoutSessionCompletedSkipsAnUnrecognizedPriceId() throws Exception {
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(checkoutCompletedEvent());
    String unknownPriceJson = SUBSCRIPTION_JSON.replace("price_pro", "price_unknown");
    com.stripe.model.Subscription stripeSubscription =
        ApiResource.GSON.fromJson(unknownPriceJson, com.stripe.model.Subscription.class);
    when(stripeClient.v1().subscriptions().retrieve("sub_123")).thenReturn(stripeSubscription);

    service.handleWebhook("payload", "sig");

    verify(subscriptionService, never()).upsertFromCheckout(any(), any(), any(), any(), any());
  }

  @Test
  void subscriptionUpdatedUpdatesStatusPlanAndPeriodEnd() throws Exception {
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(subscriptionEvent("customer.subscription.updated"));

    service.handleWebhook("payload", "sig");

    verify(subscriptionService)
        .updateFromStripeSubscription(
            "sub_123",
            SubscriptionStatus.ACTIVE,
            SubscriptionPlan.PRO,
            Instant.ofEpochSecond(1700000000));
  }

  @Test
  void subscriptionDeletedMarksTheRowCanceled() throws Exception {
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(subscriptionEvent("customer.subscription.deleted"));

    service.handleWebhook("payload", "sig");

    verify(subscriptionService).markCanceled("sub_123");
  }

  @Test
  void subscriptionDeletedWithMismatchedApiVersionStillDeserializesViaFallback() throws Exception {
    // Stripe stamps every real webhook event with the *account's* configured api_version, not
    // the SDK's compiled-in Stripe.API_VERSION. getObject() is empty on any mismatch; the
    // deserializeUnsafe() fallback must still succeed so the webhook lifecycle isn't broken
    // against real Stripe traffic.
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(subscriptionEvent("customer.subscription.deleted", "2020-08-27"));

    service.handleWebhook("payload", "sig");

    verify(subscriptionService).markCanceled("sub_123");
  }

  @Test
  void unhandledEventTypesAreIgnoredWithoutError() throws Exception {
    String json =
        """
{"id":"evt_3","object":"event","type":"invoice.paid","data":{"object":{"id":"in_1","object":"invoice"}}}
""";
    when(stripeClient.constructEvent(anyString(), anyString(), eq("whsec_test")))
        .thenReturn(ApiResource.GSON.fromJson(json, Event.class));

    service.handleWebhook("payload", "sig");

    verify(subscriptionService, never()).upsertFromCheckout(any(), any(), any(), any(), any());
    verify(subscriptionService, never()).markCanceled(any());
  }
}
