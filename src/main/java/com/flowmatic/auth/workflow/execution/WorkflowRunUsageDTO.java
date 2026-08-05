package com.flowmatic.auth.workflow.execution;

/** A user's lifetime workflow-run usage. {@code limit}/{@code remaining} are null when unlimited. */
public record WorkflowRunUsageDTO(int used, Integer limit, Integer remaining, boolean unlimited) {}
