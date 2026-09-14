package com.flowmatic.authentication.exception;

public class InvalidResetTokenException extends RuntimeException {

  public InvalidResetTokenException(String message) {
    super(message);
  }
}
