package com.flowmatic.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
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

  @MockitoBean JavaMailSender mailSender;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @BeforeEach
  void stubMimeMessageCreation() {
    // A fresh MimeMessage per call — tests that call forgot-password more than once must not
    // silently share (and overwrite) the same captured message.
    when(mailSender.createMimeMessage())
        .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
  }

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

  private String extractToken(MimeMessage message) throws Exception {
    // MimeMessageHelper's boolean-true constructor is MULTIPART_MODE_MIXED_RELATED, which nests
    // mixed -> related -> alternative even with no attachments/inline images (see EmailServiceImplTest
    // for the same traversal, verified against Spring's actual MimeMessageHelper source).
    MimeMultipart mixed = (MimeMultipart) message.getContent();
    MimeMultipart related = (MimeMultipart) mixed.getBodyPart(0).getContent();
    MimeMultipart alternative = (MimeMultipart) related.getBodyPart(0).getContent();
    String plainText = (String) alternative.getBodyPart(0).getContent();
    Matcher matcher = Pattern.compile("token=(\\S+)").matcher(plainText);
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

    verify(mailSender).send(any(MimeMessage.class));
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

    verify(mailSender, never()).send(any(MimeMessage.class));
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

    ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mailSender).send(captor.capture());
    String token = extractToken(captor.getValue());

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
