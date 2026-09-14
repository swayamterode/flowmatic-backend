package com.flowmatic.authentication.service;

import com.flowmatic.authentication.dto.AuthResponse;
import com.flowmatic.authentication.dto.LoginRequest;
import com.flowmatic.authentication.dto.RegisterRequest;
import com.flowmatic.authentication.dto.ResendOtpRequest;
import com.flowmatic.authentication.dto.VerifyEmailRequest;
import com.flowmatic.common.dto.MessageResponse;

public interface AuthService {
  MessageResponse register(RegisterRequest request);

  AuthResponse login(LoginRequest request);

  AuthResponse refreshToken(String refreshToken);

  MessageResponse verifyEmail(VerifyEmailRequest request);

  MessageResponse resendOtp(ResendOtpRequest request);
}
