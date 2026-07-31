package com.flowmatic.auth.workflow.ai;

/**
 * The single failure signal from {@link MetaPromptService}. A model outage and an unusable model
 * response take the same path, so the controller has one thing to catch.
 */
public class MetaPromptException extends RuntimeException {

  public MetaPromptException(String message) {
    super(message);
  }

  public MetaPromptException(String message, Throwable cause) {
    super(message, cause);
  }
}
