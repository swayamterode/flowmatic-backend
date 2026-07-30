package com.flowmatic.auth.workflow.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Google OAuth2 / Drive settings. Secrets come from the environment; safe empty defaults. */
@Component
public class GoogleIntegrationProperties {

  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String scope;
  private final String authUri;
  private final String tokenUri;

  public GoogleIntegrationProperties(
      @Value("${app.integrations.google.client-id:}") String clientId,
      @Value("${app.integrations.google.client-secret:}") String clientSecret,
      @Value(
              "${app.integrations.google.redirect-uri:http://localhost:8080/api/integrations/google/callback}")
          String redirectUri,
      @Value("${app.integrations.google.scope:https://www.googleapis.com/auth/drive.readonly}")
          String scope,
      @Value("${app.integrations.google.auth-uri:https://accounts.google.com/o/oauth2/v2/auth}")
          String authUri,
      @Value("${app.integrations.google.token-uri:https://oauth2.googleapis.com/token}")
          String tokenUri) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.scope = scope;
    this.authUri = authUri;
    this.tokenUri = tokenUri;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecret() {
    return clientSecret;
  }

  public String redirectUri() {
    return redirectUri;
  }

  public String scope() {
    return scope;
  }

  public String authUri() {
    return authUri;
  }

  public String tokenUri() {
    return tokenUri;
  }
}
