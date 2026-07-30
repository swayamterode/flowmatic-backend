package com.flowmatic.auth.workflow.expression;

/** Thrown when a {@code {{path}}} placeholder references data that does not exist. */
public class TemplateResolutionException extends RuntimeException {
  public TemplateResolutionException(String message) {
    super(message);
  }
}
