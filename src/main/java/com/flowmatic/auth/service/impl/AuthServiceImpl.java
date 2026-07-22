package com.flowmatic.auth.service.impl;

import com.flowmatic.auth.dto.AuthResponse;
import com.flowmatic.auth.dto.LoginRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.RegisterRequest;
import com.flowmatic.auth.dto.ResendOtpRequest;
import com.flowmatic.auth.dto.VerifyEmailRequest;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.exception.InvalidOtpException;
import com.flowmatic.auth.exception.InvalidTokenException;
import com.flowmatic.auth.exception.UserAlreadyExistsException;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.AuthService;
import com.flowmatic.auth.service.OtpService;
import com.flowmatic.auth.service.strategy.AuthProviderType;
import com.flowmatic.auth.service.strategy.AuthStrategyFactory;
import com.flowmatic.auth.service.strategy.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;
  private final AuthStrategyFactory authStrategyFactory;
  private final OtpService otpService;

  @Override
  @Transactional
  public MessageResponse register(RegisterRequest request) {
    User user = userRepository.findByEmail(request.getEmail()).orElse(null);

    // Email already registered AND verified — hard stop.
    if (user != null && user.isEmailVerified()) {
      throw new UserAlreadyExistsException("An account with this email already exists");
    }

    if (user == null) {
      user =
          User.builder()
              .email(request.getEmail())
              .passwordHash(passwordEncoder.encode(request.getPassword()))
              .fullName(request.getFullName())
              .role(Role.USER)
              .build();
    } else {
      // Account exists but was never verified — let the user re-register with fresh details
      // instead of locking them out.
      user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
      user.setFullName(request.getFullName());
    }

    userRepository.save(user);
    otpService.generateAndSend(user.getEmail());

    return MessageResponse.builder()
        .message(
            "Registration successful. An OTP has been sent to your email. "
                + "Please verify to activate your account.")
        .build();
  }

  @Override
  public AuthResponse login(LoginRequest request) {
    AuthenticatedUser authenticatedUser =
        authStrategyFactory.getStrategy(AuthProviderType.LOCAL).authenticate(request);

    return buildAuthResponse(authenticatedUser.getEmail(), authenticatedUser.getFullName());
  }

  @Override
  public AuthResponse refreshToken(String refreshToken) {
    if (!jwtUtil.isTokenValid(refreshToken)
        || !"refresh".equals(jwtUtil.extractTokenType(refreshToken))) {
      throw new InvalidTokenException("Refresh token is invalid or expired");
    }

    String email = jwtUtil.extractEmail(refreshToken);
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or expired"));

    return buildAuthResponse(user.getEmail(), user.getFullName());
  }

  @Override
  @Transactional
  public MessageResponse verifyEmail(VerifyEmailRequest request) {
    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new InvalidOtpException("No account found for this email"));

    if (user.isEmailVerified()) {
      return MessageResponse.builder()
          .message("Email is already verified. You can log in.")
          .build();
    }

    // Throws InvalidOtpException on any failure (missing/expired/too-many-attempts/wrong code).
    otpService.verify(request.getEmail(), request.getOtp());

    user.setEmailVerified(true);
    userRepository.save(user);

    return MessageResponse.builder()
        .message("Email verified successfully. You can now log in.")
        .build();
  }

  @Override
  @Transactional
  public MessageResponse resendOtp(ResendOtpRequest request) {
    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new InvalidOtpException("No account found for this email"));

    if (user.isEmailVerified()) {
      return MessageResponse.builder()
          .message("Email is already verified. You can log in.")
          .build();
    }

    otpService.resend(request.getEmail());

    return MessageResponse.builder().message("A new OTP has been sent to your email.").build();
  }

  private AuthResponse buildAuthResponse(String email, String fullName) {
    String accessToken = jwtUtil.generateAccessToken(email);
    String refreshToken = jwtUtil.generateRefreshToken(email);

    return AuthResponse.builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .tokenType("Bearer")
        .expiresInSeconds(jwtUtil.getAccessTokenExpirySeconds())
        .email(email)
        .fullName(fullName)
        .build();
  }
}
