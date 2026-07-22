package com.flowmatic.auth.service;

import com.flowmatic.auth.dto.AuthResponse;
import com.flowmatic.auth.dto.LoginRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.RegisterRequest;
import com.flowmatic.auth.dto.ResendOtpRequest;
import com.flowmatic.auth.dto.VerifyEmailRequest;

public interface AuthService {
  MessageResponse register(RegisterRequest request);

  AuthResponse login(LoginRequest request);

  AuthResponse refreshToken(String refreshToken);

  MessageResponse verifyEmail(VerifyEmailRequest request);

  MessageResponse resendOtp(ResendOtpRequest request);
}
