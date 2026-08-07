package com.flowmatic.auth.billing.web;

import com.flowmatic.auth.billing.StripeCheckoutService;
import com.flowmatic.auth.billing.StripeWebhookService;
import com.flowmatic.auth.workflow.web.CurrentUser;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts a Stripe Checkout session and receives Stripe's webhook events. */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

  private final StripeCheckoutService checkoutService;
  private final StripeWebhookService webhookService;
  private final CurrentUser currentUser;

  public BillingController(
      StripeCheckoutService checkoutService,
      StripeWebhookService webhookService,
      CurrentUser currentUser) {
    this.checkoutService = checkoutService;
    this.webhookService = webhookService;
    this.currentUser = currentUser;
  }

  @PostMapping("/checkout-session")
  public ResponseEntity<?> checkoutSession(
      @Valid @RequestBody CheckoutSessionRequest request, Authentication authentication)
      throws StripeException {
    Long userId = currentUser.requireUserId(authentication);
    String checkoutUrl = checkoutService.createCheckoutSession(userId, request.plan());
    return ResponseEntity.ok(Map.of("checkoutUrl", checkoutUrl));
  }

  /**
   * Public — authenticated by Stripe's signature (verified inside {@code webhookService}), not a
   * JWT.
   */
  @PostMapping("/webhook")
  public ResponseEntity<?> webhook(
      @RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader)
      throws StripeException {
    try {
      webhookService.handleWebhook(payload, sigHeader);
    } catch (SignatureVerificationException e) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok().build();
  }

  /**
   * A malformed body (e.g. an unrecognized enum value for {@code plan}) fails during argument
   * resolution, before the method body runs. In this Spring version {@code
   * HttpMessageNotReadableException} doesn't implement {@code
   * org.springframework.web.ErrorResponse}, so {@link
   * com.flowmatic.auth.exception.GlobalExceptionHandler}'s catch-all would otherwise flatten it to
   * a 500; handle it locally instead of widening that shared, app-wide fallback.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> handleMalformedBody(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest().build();
  }
}
