package com.flowmatic.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.dto.ForgotPasswordRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.ResetPasswordRequest;
import com.flowmatic.auth.entity.PasswordResetToken;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.exception.InvalidResetTokenException;
import com.flowmatic.auth.exception.PasswordResetCooldownException;
import com.flowmatic.auth.repository.PasswordResetTokenRepository;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.EmailService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetServiceTest {

  private static final String GENERIC_MESSAGE =
      "If an account exists for this email, a reset link has been sent.";

  private final PasswordResetTokenRepository tokenRepository =
      mock(PasswordResetTokenRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final EmailService emailService = mock(EmailService.class);

  private final PasswordResetServiceImpl service =
      new PasswordResetServiceImpl(
          tokenRepository,
          userRepository,
          passwordEncoder,
          emailService,
          30,
          60,
          "http://localhost:5173/reset-password");

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private ForgotPasswordRequest forgotRequest(String email) {
    ForgotPasswordRequest request = new ForgotPasswordRequest();
    request.setEmail(email);
    return request;
  }

  private ResetPasswordRequest resetRequest(String token, String newPassword) {
    ResetPasswordRequest request = new ResetPasswordRequest();
    request.setToken(token);
    request.setNewPassword(newPassword);
    return request;
  }

  @Test
  void requestResetIssuesATokenAndEmailsALinkForAKnownEmail() {
    when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
    when(tokenRepository.findByEmail("known@example.com")).thenReturn(Optional.empty());

    MessageResponse response = service.requestReset(forgotRequest("known@example.com"));

    assertThat(response.getMessage()).isEqualTo(GENERIC_MESSAGE);

    ArgumentCaptor<PasswordResetToken> tokenCaptor =
        ArgumentCaptor.forClass(PasswordResetToken.class);
    verify(tokenRepository).save(tokenCaptor.capture());
    PasswordResetToken saved = tokenCaptor.getValue();
    assertThat(saved.getEmail()).isEqualTo("known@example.com");
    assertThat(saved.getTokenHash()).isNotBlank();
    assertThat(saved.getExpiresAt()).isAfter(Instant.now());

    ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailService).sendPasswordResetEmail(eq("known@example.com"), linkCaptor.capture());
    assertThat(linkCaptor.getValue()).startsWith("http://localhost:5173/reset-password?token=");
  }

  @Test
  void requestResetReturnsGenericMessageAndSendsNothingForAnUnknownEmail() {
    when(userRepository.existsByEmail("unknown@example.com")).thenReturn(false);

    MessageResponse response = service.requestReset(forgotRequest("unknown@example.com"));

    assertThat(response.getMessage()).isEqualTo(GENERIC_MESSAGE);
    verify(tokenRepository, never()).save(any());
    verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
  }

  @Test
  void requestResetThrowsWhenCalledAgainWithinTheCooldownWindow() {
    when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
    PasswordResetToken existing =
        PasswordResetToken.builder()
            .id(1L)
            .email("known@example.com")
            .tokenHash("old-hash")
            .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
            .createdAt(Instant.now().minusSeconds(10))
            .build();
    when(tokenRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.requestReset(forgotRequest("known@example.com")))
        .isInstanceOf(PasswordResetCooldownException.class);

    verify(tokenRepository, never()).save(any());
  }

  @Test
  void requestResetAllowsAnotherRequestOnceTheCooldownHasPassed() {
    when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
    PasswordResetToken existing =
        PasswordResetToken.builder()
            .id(1L)
            .email("known@example.com")
            .tokenHash("old-hash")
            .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
            .createdAt(Instant.now().minusSeconds(61))
            .build();
    when(tokenRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));

    service.requestReset(forgotRequest("known@example.com"));

    verify(tokenRepository).save(existing);
    verify(emailService).sendPasswordResetEmail(eq("known@example.com"), anyString());
  }

  @Test
  void resetPasswordUpdatesThePasswordAndDeletesTheToken() throws Exception {
    String rawToken = "raw-token-value";
    PasswordResetToken record =
        PasswordResetToken.builder()
            .id(2L)
            .email("known@example.com")
            .tokenHash(sha256(rawToken))
            .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(record));
    User user =
        User.builder()
            .id(5L)
            .email("known@example.com")
            .passwordHash("old-hash")
            .role(Role.USER)
            .build();
    when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(user));

    MessageResponse response = service.resetPassword(resetRequest(rawToken, "newPassword123"));

    assertThat(response.getMessage()).isEqualTo("Password reset successfully. You can now log in.");
    assertThat(passwordEncoder.matches("newPassword123", user.getPasswordHash())).isTrue();
    verify(userRepository).save(user);
    verify(tokenRepository).delete(record);
  }

  @Test
  void resetPasswordRejectsAnUnknownToken() {
    when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resetPassword(resetRequest("bogus-token", "newPassword123")))
        .isInstanceOf(InvalidResetTokenException.class);
  }

  @Test
  void resetPasswordRejectsAnExpiredTokenAndDeletesIt() throws Exception {
    String rawToken = "expired-token";
    PasswordResetToken record =
        PasswordResetToken.builder()
            .id(3L)
            .email("known@example.com")
            .tokenHash(sha256(rawToken))
            .expiresAt(Instant.now().minusSeconds(1))
            .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .build();
    when(tokenRepository.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(record));

    assertThatThrownBy(() -> service.resetPassword(resetRequest(rawToken, "newPassword123")))
        .isInstanceOf(InvalidResetTokenException.class);

    verify(tokenRepository).delete(record);
    verify(userRepository, never()).save(any());
  }
}
