package com.flowmatic.billing.web;

import com.flowmatic.billing.entity.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/billing/checkout-session}. */
public record CheckoutSessionRequest(@NotNull SubscriptionPlan plan) {}
