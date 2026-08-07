package com.flowmatic.auth.billing;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the single {@link StripeClient} bean every Stripe-facing collaborator injects. */
@Configuration
public class StripeConfig {

  @Bean
  public StripeClient stripeClient(@Value("${app.stripe.secret-key:}") String secretKey) {
    return new StripeClient(secretKey);
  }
}
