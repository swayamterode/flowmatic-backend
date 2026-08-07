package com.flowmatic.auth.service.impl;

import com.flowmatic.auth.dto.ForgotPasswordRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.ResetPasswordRequest;
import com.flowmatic.auth.entity.PasswordResetToken;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.exception.InvalidResetTokenException;
import com.flowmatic.auth.exception.PasswordResetCooldownException;
import com.flowmatic.auth.repository.PasswordResetTokenRepository;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.EmailService;
import com.flowmatic.auth.service.PasswordResetService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetServiceImpl implements PasswordResetService {

  private static final String GENERIC_MESSAGE =
      "If an account exists for this email, a reset link has been sent.";

  private final PasswordResetTokenRepository tokenRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailService emailService;
  private final long expiryMinutes;
  private final long resendCooldownSeconds;
  private final String resetPasswordUrl;
  private final SecureRandom secureRandom = new SecureRandom();

  public PasswordResetServiceImpl(
      PasswordResetTokenRepository tokenRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      EmailService emailService,
      @Value("${app.password-reset.expiry-minutes:30}") long expiryMinutes,
      @Value("${app.password-reset.resend-cooldown-seconds:60}") long resendCooldownSeconds,
      @Value("${app.frontend.reset-password-url:http://localhost:5173/reset-password}")
          String resetPasswordUrl) {
    this.tokenRepository = tokenRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.emailService = emailService;
    this.expiryMinutes = expiryMinutes;
    this.resendCooldownSeconds = resendCooldownSeconds;
    this.resetPasswordUrl = resetPasswordUrl;
  }

  @Override
  @Transactional
  public MessageResponse requestReset(ForgotPasswordRequest request) {
    String email = request.getEmail();

    if (!userRepository.existsByEmail(email)) {
      return MessageResponse.builder().message(GENERIC_MESSAGE).build();
    }

    PasswordResetToken token =
        tokenRepository.findByEmail(email).orElseGet(PasswordResetToken::new);

    // token.getId() is only non-null when we loaded an existing row above.
    if (token.getId() != null) {
      long secondsSince = Duration.between(token.getCreatedAt(), Instant.now()).getSeconds();
      if (secondsSince < resendCooldownSeconds) {
        throw new PasswordResetCooldownException(
            "Please wait "
                + (resendCooldownSeconds - secondsSince)
                + " seconds before requesting another reset link.");
      }
    }

    String rawToken = generateRawToken();
    Instant now = Instant.now();
    token.setEmail(email);
    token.setTokenHash(sha256(rawToken));
    token.setExpiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES));
    token.setCreatedAt(now);
    tokenRepository.save(token);

    emailService.sendPasswordResetEmail(email, resetPasswordUrl + "?token=" + rawToken);

    return MessageResponse.builder().message(GENERIC_MESSAGE).build();
  }

  @Override
  @Transactional
  public MessageResponse resetPassword(ResetPasswordRequest request) {
    PasswordResetToken token =
        tokenRepository
            .findByTokenHash(sha256(request.getToken()))
            .orElseThrow(() -> new InvalidResetTokenException("Invalid or expired reset link."));

    if (Instant.now().isAfter(token.getExpiresAt())) {
      tokenRepository.delete(token);
      throw new InvalidResetTokenException("Invalid or expired reset link.");
    }

    User user =
        userRepository
            .findByEmail(token.getEmail())
            .orElseThrow(() -> new InvalidResetTokenException("Invalid or expired reset link."));

    user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
    userRepository.save(user);
    tokenRepository.delete(token);

    return MessageResponse.builder()
        .message("Password reset successfully. You can now log in.")
        .build();
  }

  private String generateRawToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
