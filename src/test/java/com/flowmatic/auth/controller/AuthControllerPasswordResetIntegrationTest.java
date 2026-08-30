package com.flowmatic.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerPasswordResetIntegrationTest {

  private static final String GENERIC_MESSAGE =
      "If an account exists for this email, a reset link has been sent.";

  // sendPasswordResetEmail() is @Async, so the real send happens on a background thread after
  // the HTTP response returns — verify() below uses Mockito's timeout() to poll for it instead
  // of asserting immediately.
  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  private void seedVerifiedUser(String email, String password) {
    userRepository
        .findByEmail(email)
        .orElseGet(
            () ->
                userRepository.save(
                    User.builder()
                        .email(email)
                        .fullName("Reset Test User")
                        .passwordHash(passwordEncoder.encode(password))
                        .role(Role.USER)
                        .emailVerified(true)
                        .build()));
  }

  private String extractToken(String plainTextBody) {
    Matcher matcher = Pattern.compile("token=(\\S+)").matcher(plainTextBody);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  @Test
  void forgotPasswordForARegisteredEmailReturnsTheGenericMessageAndEmailsALink() throws Exception {
    seedVerifiedUser("forgot-happy@example.com", "oldPassword123");

    mockMvc
        .perform(
            post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"forgot-happy@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE));

    verify(resendEmailService, timeout(2000))
        .send(eq("forgot-happy@example.com"), anyString(), anyString(), anyString());
  }

  @Test
  void forgotPasswordForAnUnknownEmailReturnsTheSameGenericMessageAndSendsNothing()
      throws Exception {
    mockMvc
        .perform(
            post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"no-such-user@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE));

    verify(resendEmailService, never())
        .send(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void forgotPasswordWithAMalformedEmailReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void secondForgotPasswordRequestWithinTheCooldownReturns429() throws Exception {
    seedVerifiedUser("cooldown@example.com", "oldPassword123");

    mockMvc.perform(
        post("/api/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"cooldown@example.com\"}"));

    mockMvc
        .perform(
            post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"cooldown@example.com\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void resetPasswordWithAValidTokenChangesThePasswordAndConsumesTheToken() throws Exception {
    seedVerifiedUser("reset-roundtrip@example.com", "oldPassword123");

    mockMvc.perform(
        post("/api/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"reset-roundtrip@example.com\"}"));

    ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService, timeout(2000))
        .send(eq("reset-roundtrip@example.com"), anyString(), textCaptor.capture(), anyString());
    String token = extractToken(textCaptor.getValue());

    mockMvc
        .perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"brandNewPassword456\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.message").value("Password reset successfully. You can now log in."));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"reset-roundtrip@example.com\",\"password\":\"brandNewPassword456\"}"))
        .andExpect(status().isOk());

    // The same token cannot be used twice.
    mockMvc
        .perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"anotherPassword789\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void resetPasswordWithAnUnknownTokenReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bogus-token\",\"newPassword\":\"somePassword123\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void resetPasswordWithATooShortNewPasswordReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"whatever\",\"newPassword\":\"short\"}"))
        .andExpect(status().isBadRequest());
  }
}
