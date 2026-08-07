package com.flowmatic.auth.billing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowmatic.auth.billing.StripeCheckoutService;
import com.flowmatic.auth.billing.StripeWebhookService;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.SignatureVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingControllerIntegrationTest {

  private static final String CALLER = "billing-caller@example.com";

  @MockitoBean JavaMailSender mailSender;
  @MockitoBean StripeCheckoutService checkoutService;
  @MockitoBean StripeWebhookService webhookService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;

  // CurrentUser.requireUserId resolves the JWT subject to a persisted User row; @WithMockUser
  // only fakes the Authentication, so the row must exist. Idempotent: the context (and its H2
  // schema) is shared across every test method in this class.
  @BeforeEach
  void seedCallerUser() {
    userRepository
        .findByEmail(CALLER)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(CALLER)
                        .fullName("Billing Caller")
                        .passwordHash("x")
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  @Test
  @WithMockUser(username = CALLER)
  void checkoutSessionHappyPathReturnsTheCheckoutUrl() throws Exception {
    when(checkoutService.createCheckoutSession(any(), eq(SubscriptionPlan.PRO)))
        .thenReturn("https://checkout.stripe.com/c/pay/cs_test_1");

    mockMvc
        .perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PRO\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe.com/c/pay/cs_test_1"));
  }

  @Test
  @WithMockUser(username = CALLER)
  void checkoutSessionReturns409WhenAlreadySubscribed() throws Exception {
    when(checkoutService.createCheckoutSession(any(), any()))
        .thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "You already have an active subscription"));

    mockMvc
        .perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"ESSENTIALS\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(username = CALLER)
  void checkoutSessionReturns400ForAnInvalidPlanValue() throws Exception {
    mockMvc
        .perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest());

    verify(checkoutService, never()).createCheckoutSession(any(), any());
  }

  @Test
  @WithMockUser(username = CALLER)
  void checkoutSessionReturns502WhenStripeIsUnreachable() throws Exception {
    when(checkoutService.createCheckoutSession(any(), any()))
        .thenThrow(new ApiConnectionException("stripe unreachable"));

    mockMvc
        .perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PRO\"}"))
        .andExpect(status().isBadGateway());
  }

  @Test
  void webhookRejectsATamperedPayloadWithNoAuthenticationRequired() throws Exception {
    doThrow(new SignatureVerificationException("bad signature", "t=1,v1=bad"))
        .when(webhookService)
        .handleWebhook(anyString(), anyString());

    mockMvc
        .perform(
            post("/api/billing/webhook")
                .header("Stripe-Signature", "t=1,v1=bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void webhookHappyPathReturns200() throws Exception {
    mockMvc
        .perform(
            post("/api/billing/webhook")
                .header("Stripe-Signature", "t=1,v1=good")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }
}
