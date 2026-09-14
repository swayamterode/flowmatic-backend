package com.flowmatic.authentication.exception;

public class PasswordResetCooldownException extends RuntimeException {

  public PasswordResetCooldownException(String message) {
    super(message);
  }
}
