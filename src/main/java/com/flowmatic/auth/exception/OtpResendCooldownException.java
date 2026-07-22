package com.flowmatic.auth.exception;

public class OtpResendCooldownException extends RuntimeException {

  public OtpResendCooldownException(String message) {
    super(message);
  }
}
