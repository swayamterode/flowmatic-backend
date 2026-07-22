package com.flowmatic.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(nullable = false)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(nullable = false)
  private boolean enabled;

  // True only after the user confirms their email via OTP. Login is blocked until then.
  @Column(nullable = false)
  private boolean emailVerified;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void onCreated() {
    this.createdAt = Instant.now();
    if (this.role == null) {
      this.role = Role.USER;
    }
    this.enabled = true;
  }
}
