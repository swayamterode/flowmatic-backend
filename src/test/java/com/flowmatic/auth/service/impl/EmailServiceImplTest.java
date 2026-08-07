package com.flowmatic.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

class EmailServiceImplTest {

  private final JavaMailSender mailSender = mock(JavaMailSender.class);
  private final EmailServiceImpl emailService = new EmailServiceImpl(mailSender);

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(emailService, "fromAddress", "no-reply@flowmatic.com");
    ReflectionTestUtils.setField(emailService, "expiryMinutes", 10L);
    ReflectionTestUtils.setField(emailService, "resetLinkExpiryMinutes", 30L);
  }

  @Test
  void sendPasswordResetEmailSendsAMessageContainingTheLink() throws Exception {
    MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
    when(mailSender.createMimeMessage()).thenReturn(realMessage);

    String resetLink = "http://localhost:5173/reset-password?token=abc123";
    emailService.sendPasswordResetEmail("user@example.com", resetLink);

    verify(mailSender).send(realMessage);
    assertThat(realMessage.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
    assertThat(realMessage.getSubject()).isEqualTo("Reset your FlowMatic password");

    // MimeMessageHelper's boolean-true constructor is MULTIPART_MODE_MIXED_RELATED, which nests
    // mixed -> related -> alternative even with no attachments/inline images; setText(plain,
    // html) populates the innermost "alternative" multipart with the two text parts.
    MimeMultipart mixed = (MimeMultipart) realMessage.getContent();
    MimeMultipart related = (MimeMultipart) mixed.getBodyPart(0).getContent();
    MimeMultipart alternative = (MimeMultipart) related.getBodyPart(0).getContent();
    assertThat(alternative.getCount()).isEqualTo(2);
    String plainText = (String) alternative.getBodyPart(0).getContent();
    String html = (String) alternative.getBodyPart(1).getContent();
    assertThat(plainText).contains(resetLink);
    assertThat(html).contains(resetLink);
  }
}
