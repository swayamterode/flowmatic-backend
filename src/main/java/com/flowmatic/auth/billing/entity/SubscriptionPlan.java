package com.flowmatic.auth.billing.entity;

/** A purchasable billing tier; each raises or removes the free-tier workflow run quota. */
public enum SubscriptionPlan {
  ESSENTIALS,
  PRO,
  ENTERPRISE
}
