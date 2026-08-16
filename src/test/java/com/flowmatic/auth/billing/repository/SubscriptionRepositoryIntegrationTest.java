package com.flowmatic.auth.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.billing.entity.Subscription;
import com.flowmatic.auth.billing.entity.SubscriptionPlan;
import com.flowmatic.auth.billing.entity.SubscriptionStatus;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionRepositoryIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired UserRepository userRepository;
  @Autowired SubscriptionRepository subscriptionRepository;

  private User newUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  @Test
  void savesAndFindsByUserIdAndByStripeSubscriptionId() {
    User user = newUser("sub-repo@example.com");
    Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    subscriptionRepository.save(
        Subscription.builder()
            .user(user)
            .stripeCustomerId("cus_123")
            .stripeSubscriptionId("sub_123")
            .plan(SubscriptionPlan.PRO)
            .status(SubscriptionStatus.ACTIVE)
            .currentPeriodEnd(periodEnd)
            .build());

    assertThat(subscriptionRepository.findByUserId(user.getId()))
        .isPresent()
        .get()
        .satisfies(
            s -> {
              assertThat(s.getPlan()).isEqualTo(SubscriptionPlan.PRO);
              assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
              assertThat(s.getCurrentPeriodEnd()).isEqualTo(periodEnd);
              assertThat(s.getCreatedAt()).isNotNull();
              assertThat(s.getUpdatedAt()).isNotNull();
            });

    assertThat(subscriptionRepository.findByStripeSubscriptionId("sub_123"))
        .isPresent()
        .get()
        .extracting(Subscription::getStripeCustomerId)
        .isEqualTo("cus_123");

    assertThat(subscriptionRepository.findByStripeSubscriptionId("no-such-id")).isEmpty();
  }
}
