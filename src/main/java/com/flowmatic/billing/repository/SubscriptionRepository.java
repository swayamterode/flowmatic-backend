package com.flowmatic.billing.repository;

import com.flowmatic.billing.entity.Subscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  Optional<Subscription> findByUserId(Long userId);

  Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
