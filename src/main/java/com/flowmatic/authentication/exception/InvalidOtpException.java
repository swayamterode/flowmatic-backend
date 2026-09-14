package com.flowmatic.authentication.exception;

public class InvalidOtpException extends RuntimeException {

  public InvalidOtpException(String message) {
    super(message);
  }
}
