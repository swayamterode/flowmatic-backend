package com.flowmatic.auth.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

class StripeConfigTest {

  @Test
  void createsAStripeClientWithTheConfiguredKey() {
    StripeClient client = new StripeConfig().stripeClient("sk_test_123");

    assertThat(client).isNotNull();
  }
}
