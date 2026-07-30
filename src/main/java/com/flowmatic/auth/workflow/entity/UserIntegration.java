package com.flowmatic.auth.workflow.entity;

import com.flowmatic.auth.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An external provider connection (e.g. Google Drive) for a user, holding OAuth tokens. One row per
 * (user, provider).
 */
@Entity
@Table(
    name = "user_integrations",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_user_integrations_user_provider",
            columnNames = {"user_id", "provider"}))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserIntegration {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 50)
  private String provider;

  @Column(name = "access_token", length = 2048)
  private String accessToken;

  @Column(name = "refresh_token", length = 2048)
  private String refreshToken;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
