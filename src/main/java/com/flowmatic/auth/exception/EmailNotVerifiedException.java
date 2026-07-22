package com.flowmatic.auth.exception;

public class EmailNotVerifiedException extends RuntimeException {

  public EmailNotVerifiedException(String message) {
    super(message);
  }
}
