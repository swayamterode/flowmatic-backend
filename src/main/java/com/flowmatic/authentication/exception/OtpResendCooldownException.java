package com.flowmatic.authentication.exception;

public class OtpResendCooldownException extends RuntimeException {

  public OtpResendCooldownException(String message) {
    super(message);
  }
}
