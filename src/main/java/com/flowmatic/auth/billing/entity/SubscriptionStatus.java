package com.flowmatic.auth.billing.entity;

/** Lifecycle status of a single {@link Subscription}, kept in sync via Stripe webhooks. */
public enum SubscriptionStatus {
  ACTIVE,
  PAST_DUE,
  CANCELED
}
