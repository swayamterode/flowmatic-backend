package com.flowmatic.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class EmailServiceImplTest {

  private final ResendEmailService resendEmailService = mock(ResendEmailService.class);
  private final EmailServiceImpl emailService = new EmailServiceImpl(resendEmailService);

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(emailService, "expiryMinutes", 10L);
    ReflectionTestUtils.setField(emailService, "resetLinkExpiryMinutes", 30L);
  }

  @Test
  void sendPasswordResetEmailSendsAMessageContainingTheLink() {
    String resetLink = "http://localhost:5173/reset-password?token=abc123";
    emailService.sendPasswordResetEmail("user@example.com", resetLink);

    ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService)
        .send(
            eq("user@example.com"),
            eq("Reset your FlowMatic password"),
            textCaptor.capture(),
            htmlCaptor.capture());

    assertThat(textCaptor.getValue()).contains(resetLink);
    assertThat(htmlCaptor.getValue()).contains(resetLink);
  }

  @Test
  void sendOtpEmailSendsAMessageContainingTheCode() {
    emailService.sendOtpEmail("user@example.com", "123456");

    ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
    verify(resendEmailService)
        .send(
            eq("user@example.com"),
            eq("Your FlowMatic verification code"),
            textCaptor.capture(),
            htmlCaptor.capture());

    assertThat(textCaptor.getValue()).contains("123456");
    assertThat(htmlCaptor.getValue()).contains("123456");
  }
}
