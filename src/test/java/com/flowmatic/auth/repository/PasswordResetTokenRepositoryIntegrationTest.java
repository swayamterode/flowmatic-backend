package com.flowmatic.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.entity.PasswordResetToken;
import com.flowmatic.auth.service.impl.ResendEmailService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTokenRepositoryIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired PasswordResetTokenRepository repository;

  @Test
  void savesAndFindsByEmailAndByTokenHash() {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    repository.save(
        PasswordResetToken.builder()
            .email("reset-repo@example.com")
            .tokenHash("abc123hash")
            .expiresAt(expiresAt)
            .createdAt(createdAt)
            .build());

    assertThat(repository.findByEmail("reset-repo@example.com"))
        .isPresent()
        .get()
        .satisfies(
            t -> {
              assertThat(t.getTokenHash()).isEqualTo("abc123hash");
              assertThat(t.getExpiresAt()).isEqualTo(expiresAt);
              assertThat(t.getCreatedAt()).isEqualTo(createdAt);
            });

    assertThat(repository.findByTokenHash("abc123hash"))
        .isPresent()
        .get()
        .extracting(PasswordResetToken::getEmail)
        .isEqualTo("reset-repo@example.com");

    assertThat(repository.findByTokenHash("no-such-hash")).isEmpty();
  }
}
