package com.flowmatic.auth.workflow.entity;

/**
 * How a {@link WorkflowRun} was started. Only {@code MANUAL} is real today — no scheduler or
 * webhook trigger path exists yet. Reserved as an enum (not a native MySQL enum) so extending the
 * vocabulary later never requires a schema migration.
 */
public enum TriggerType {
  MANUAL
}
