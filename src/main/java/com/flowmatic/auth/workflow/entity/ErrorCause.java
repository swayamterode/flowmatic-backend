package com.flowmatic.auth.workflow.entity;

/**
 * Coarse classification of why a {@link WorkflowRun} failed, for the dashboard's failures-by-cause
 * breakdown. Classified from the final error message text in {@code WorkflowExecutionService} —
 * every {@code NodeExecutor} already flattens its own exceptions into a message before a {@code
 * WorkflowRun} ever sees them, so message content is the only signal left by the time a failure is
 * persisted.
 */
public enum ErrorCause {
  TIMEOUT,
  AUTH,
  VALIDATION,
  OTHER
}
