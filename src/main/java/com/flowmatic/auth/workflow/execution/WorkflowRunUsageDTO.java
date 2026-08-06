package com.flowmatic.auth.workflow.execution;

/**
 * A user's lifetime workflow-run usage. {@code limit}/{@code remaining} are null when unlimited.
 * {@code plan} is the active plan's name, or null if the user has no active subscription.
 */
public record WorkflowRunUsageDTO(
    int used, Integer limit, Integer remaining, boolean unlimited, String plan) {}
