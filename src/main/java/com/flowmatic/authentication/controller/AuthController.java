package com.flowmatic.authentication.controller;

import com.flowmatic.authentication.dto.AuthResponse;
import com.flowmatic.authentication.dto.ForgotPasswordRequest;
import com.flowmatic.authentication.dto.LoginRequest;
import com.flowmatic.authentication.dto.RefreshTokenRequest;
import com.flowmatic.authentication.dto.RegisterRequest;
import com.flowmatic.authentication.dto.ResendOtpRequest;
import com.flowmatic.authentication.dto.ResetPasswordRequest;
import com.flowmatic.authentication.dto.VerifyEmailRequest;
import com.flowmatic.authentication.service.AuthService;
import com.flowmatic.authentication.service.PasswordResetService;
import com.flowmatic.common.dto.MessageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final PasswordResetService passwordResetService;

  @PostMapping("/register")
  public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
  }

  @PostMapping("/verify-email")
  public ResponseEntity<MessageResponse> verifyEmail(
      @Valid @RequestBody VerifyEmailRequest request) {
    return ResponseEntity.ok(authService.verifyEmail(request));
  }

  @PostMapping("/resend-otp")
  public ResponseEntity<MessageResponse> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
    return ResponseEntity.ok(authService.resendOtp(request));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }

  @PostMapping("/refresh-token")
  public ResponseEntity<AuthResponse> refreshToken(
      @Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(authService.refreshToken(request.getRefreshToken()));
  }

  @PostMapping("/forgot-password")
  public ResponseEntity<MessageResponse> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest request) {
    return ResponseEntity.ok(passwordResetService.requestReset(request));
  }

  @PostMapping("/reset-password")
  public ResponseEntity<MessageResponse> resetPassword(
      @Valid @RequestBody ResetPasswordRequest request) {
    return ResponseEntity.ok(passwordResetService.resetPassword(request));
  }
}
