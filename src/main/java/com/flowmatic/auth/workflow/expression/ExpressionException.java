package com.flowmatic.auth.workflow.expression;

/** Thrown when a FILTER/CONDITION expression cannot be parsed or evaluated. */
public class ExpressionException extends RuntimeException {
  public ExpressionException(String message) {
    super(message);
  }
}
