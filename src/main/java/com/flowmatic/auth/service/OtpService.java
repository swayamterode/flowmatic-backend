package com.flowmatic.auth.service;

public interface OtpService {
  void generateAndSend(String email);

  void resend(String email);

  void verify(String email, String otp);
}
