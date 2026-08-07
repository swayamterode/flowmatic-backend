package com.flowmatic.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "password_reset_tokens", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PasswordResetToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  // SHA-256 hex digest of the raw token. Deterministic (unlike BCrypt) so it supports an exact
  // lookup by hash alone — reset-password only ever receives the raw token, never the email.
  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(nullable = false)
  private Instant expiresAt;

  // When this token was (re)issued. Drives the resend cooldown.
  @Column(nullable = false)
  private Instant createdAt;
}
