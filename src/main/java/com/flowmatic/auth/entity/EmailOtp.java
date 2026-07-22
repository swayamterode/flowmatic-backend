package com.flowmatic.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_otp", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmailOtp {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  // We store only the BCrypt hash of the code — never the plaintext OTP.
  @Column(nullable = false)
  private String otpHash;

  @Column(nullable = false)
  private Instant expiresAt;

  // Number of wrong guesses so far. Used to lock the OTP after too many attempts.
  @Column(nullable = false)
  private int attempts;

  // The moment this code was (re)issued. Drives the resend cooldown.
  @Column(nullable = false)
  private Instant createdAt;
}
