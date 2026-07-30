package com.flowmatic.auth.workflow.entity;

/**
 * The kind of node in a workflow graph. Each value has exactly one {@code NodeExecutor}
 * implementation that knows how to run it.
 */
public enum NodeType {
  TRIGGER,
  DATA_SOURCE,
  AI,
  OUTPUT,
  HTTP,
  FILTER,
  TRANSFORM,
  CONDITION
}
