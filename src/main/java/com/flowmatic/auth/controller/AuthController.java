package com.flowmatic.auth.controller;

import com.flowmatic.auth.dto.AuthResponse;
import com.flowmatic.auth.dto.LoginRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.RefreshTokenRequest;
import com.flowmatic.auth.dto.RegisterRequest;
import com.flowmatic.auth.dto.ResendOtpRequest;
import com.flowmatic.auth.dto.VerifyEmailRequest;
import com.flowmatic.auth.service.AuthService;
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
}
