package com.flowmatic.auth.workflow.integration;

import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.UserIntegration;
import com.flowmatic.auth.workflow.repository.UserIntegrationRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stores and vends Google OAuth tokens per user in {@code user_integrations}. Access tokens are
 * refreshed lazily, only when they are within {@link #EXPIRY_BUFFER_SECONDS} of expiring — so users
 * are not forced to reconnect on every expiry.
 */
@Service
public class UserIntegrationService {

  private static final Logger log = LoggerFactory.getLogger(UserIntegrationService.class);
  public static final String GOOGLE = "google";
  private static final long EXPIRY_BUFFER_SECONDS = 60;

  private final UserIntegrationRepository repository;
  private final UserRepository userRepository;
  private final GoogleOAuthClient oAuthClient;

  public UserIntegrationService(
      UserIntegrationRepository repository,
      UserRepository userRepository,
      GoogleOAuthClient oAuthClient) {
    this.repository = repository;
    this.userRepository = userRepository;
    this.oAuthClient = oAuthClient;
  }

  public boolean isConnected(Long userId) {
    return repository
        .findByUser_IdAndProvider(userId, GOOGLE)
        .map(i -> i.getRefreshToken() != null || i.getAccessToken() != null)
        .orElse(false);
  }

  /** Upserts tokens after a successful consent exchange. A null refresh token keeps the old one. */
  public void saveGoogleTokens(Long userId, GoogleTokenResponse tokens) {
    UserIntegration integration =
        repository
            .findByUser_IdAndProvider(userId, GOOGLE)
            .orElseGet(
                () -> {
                  User user =
                      userRepository
                          .findById(userId)
                          .orElseThrow(
                              () -> new IllegalArgumentException("User not found: " + userId));
                  return UserIntegration.builder().user(user).provider(GOOGLE).build();
                });
    integration.setAccessToken(tokens.accessToken());
    if (tokens.refreshToken() != null) {
      integration.setRefreshToken(tokens.refreshToken());
    }
    integration.setExpiresAt(expiryFrom(tokens.expiresInSeconds()));
    repository.save(integration);
  }

  /** Returns a currently-valid access token, refreshing first if it is about to expire. */
  public String getValidAccessToken(Long userId) {
    UserIntegration integration =
        repository
            .findByUser_IdAndProvider(userId, GOOGLE)
            .orElseThrow(
                () -> new IntegrationNotConnectedException("Google Drive is not connected"));

    if (!needsRefresh(integration)) {
      return integration.getAccessToken();
    }
    if (integration.getRefreshToken() == null) {
      throw new IntegrationNotConnectedException(
          "Google access token expired and no refresh token is stored; reconnect required");
    }

    log.info("Refreshing Google access token for user {}", userId);
    GoogleTokenResponse refreshed = oAuthClient.refresh(integration.getRefreshToken());
    integration.setAccessToken(refreshed.accessToken());
    integration.setExpiresAt(expiryFrom(refreshed.expiresInSeconds()));
    if (refreshed.refreshToken() != null) {
      integration.setRefreshToken(refreshed.refreshToken());
    }
    repository.save(integration);
    return integration.getAccessToken();
  }

  private static boolean needsRefresh(UserIntegration integration) {
    Instant expiresAt = integration.getExpiresAt();
    return expiresAt != null
        && expiresAt.isBefore(Instant.now().plusSeconds(EXPIRY_BUFFER_SECONDS));
  }

  private static Instant expiryFrom(long expiresInSeconds) {
    return expiresInSeconds > 0 ? Instant.now().plusSeconds(expiresInSeconds) : null;
  }
}
