package com.flowmatic.common.email;

public interface EmailService {
  void sendOtpEmail(String to, String code);

  void sendPasswordResetEmail(String to, String resetLink);
}
