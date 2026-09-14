package com.flowmatic.authentication.service;

public interface OtpService {
  void generateAndSend(String email);

  void resend(String email);

  void verify(String email, String otp);
}
