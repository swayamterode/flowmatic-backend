package com.flowmatic.auth.billing.entity;

import com.flowmatic.auth.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A user's Stripe subscription, 1:1 with {@link User}. Kept in sync via Stripe webhooks. */
@Entity
@Table(
    name = "subscriptions",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_subscriptions_user", columnNames = "user_id"),
      @UniqueConstraint(
          name = "uk_subscriptions_stripe_subscription_id",
          columnNames = "stripe_subscription_id")
    })
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Subscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "stripe_customer_id", nullable = false, length = 255)
  private String stripeCustomerId;

  @Column(name = "stripe_subscription_id", nullable = false, length = 255)
  private String stripeSubscriptionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SubscriptionPlan plan;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SubscriptionStatus status;

  @Column(name = "current_period_end", nullable = false)
  private Instant currentPeriodEnd;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
