package com.flowmatic.auth.workflow.integration;

import com.flowmatic.auth.repository.UserRepository;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints to check Google Drive connection status and drive the OAuth consent flow.
 *
 * <p>{@code /status} and {@code /connect} run under the normal JWT auth (they resolve the current
 * user). {@code /callback} is reached via Google's browser redirect (no JWT), so the user id is
 * carried in the signed {@code state} parameter.
 */
@RestController
@RequestMapping("/api/integrations/google")
public class IntegrationController {

  private final UserIntegrationService integrationService;
  private final GoogleOAuthClient oAuthClient;
  private final OAuthStateCodec stateCodec;
  private final UserRepository userRepository;

  public IntegrationController(
      UserIntegrationService integrationService,
      GoogleOAuthClient oAuthClient,
      OAuthStateCodec stateCodec,
      UserRepository userRepository) {
    this.integrationService = integrationService;
    this.oAuthClient = oAuthClient;
    this.stateCodec = stateCodec;
    this.userRepository = userRepository;
  }

  @GetMapping("/status")
  public ResponseEntity<?> status(Authentication authentication) {
    Long userId = currentUserId(authentication);
    return ResponseEntity.ok(
        Map.of("provider", "google", "connected", integrationService.isConnected(userId)));
  }

  /** Starts consent: returns a 302 redirect to Google's authorization URL. */
  @GetMapping("/connect")
  public ResponseEntity<Void> connect(Authentication authentication) {
    Long userId = currentUserId(authentication);
    String url = oAuthClient.authorizationUrl(stateCodec.encode(userId));
    return ResponseEntity.status(302).location(URI.create(url)).build();
  }

  @GetMapping("/callback")
  public ResponseEntity<?> callback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error) {
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    Long userId = stateCodec.verify(state);
    GoogleTokenResponse tokens = oAuthClient.exchangeCode(code);
    integrationService.saveGoogleTokens(userId, tokens);
    return ResponseEntity.ok(Map.of("provider", "google", "connected", true));
  }

  private Long currentUserId(Authentication authentication) {
    String email = authentication.getName();
    return userRepository
        .findByEmail(email)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email))
        .getId();
  }
}
