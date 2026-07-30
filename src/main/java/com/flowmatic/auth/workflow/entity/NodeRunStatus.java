package com.flowmatic.auth.workflow.entity;

/** Lifecycle status of a single node execution, recorded in {@link NodeRunLog}. */
public enum NodeRunStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILED,
  /** The node was not reached because it sits on a branch the CONDITION node did not take. */
  SKIPPED
}
