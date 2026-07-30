package com.flowmatic.auth.workflow.entity;

/** Lifecycle status of a single {@link WorkflowRun}. */
public enum WorkflowRunStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILED
}
