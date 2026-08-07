package com.flowmatic.auth.service;

public interface EmailService {
  void sendOtpEmail(String to, String code);

  void sendPasswordResetEmail(String to, String resetLink);
}
