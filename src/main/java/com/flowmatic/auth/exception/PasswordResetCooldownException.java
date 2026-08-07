package com.flowmatic.auth.exception;

public class PasswordResetCooldownException extends RuntimeException {

  public PasswordResetCooldownException(String message) {
    super(message);
  }
}
