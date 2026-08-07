package com.flowmatic.auth.exception;

public class InvalidResetTokenException extends RuntimeException {

  public InvalidResetTokenException(String message) {
    super(message);
  }
}
