package com.flowmatic.auth.workflow.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encodes the owning user id into the OAuth {@code state} parameter and verifies it on callback.
 * Needed because Google's redirect carries no JWT, so the callback cannot otherwise identify the
 * user. The value is HMAC-signed (reusing the JWT secret) and time-bounded to resist tampering and
 * replay.
 */
@Component
public class OAuthStateCodec {

  private static final long MAX_AGE_SECONDS = 600; // 10 minutes
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  private final byte[] secret;

  public OAuthStateCodec(@Value("${app.jwt.secret}") String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String encode(Long userId) {
    String payload = userId + ":" + Instant.now().getEpochSecond();
    String encodedPayload = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encodedPayload + "." + B64.encodeToString(sign(encodedPayload));
  }

  /** Verifies the signature and freshness and returns the user id, or throws if invalid. */
  public Long verify(String state) {
    if (state == null || !state.contains(".")) {
      throw new IllegalArgumentException("Malformed OAuth state");
    }
    String[] parts = state.split("\\.", 2);
    byte[] expected = sign(parts[0]);
    byte[] actual = B64D.decode(parts[1]);
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new IllegalArgumentException("OAuth state signature mismatch");
    }
    String payload = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
    String[] fields = payload.split(":", 2);
    long issuedAt = Long.parseLong(fields[1]);
    if (Instant.now().getEpochSecond() - issuedAt > MAX_AGE_SECONDS) {
      throw new IllegalArgumentException("OAuth state expired");
    }
    return Long.parseLong(fields[0]);
  }

  private byte[] sign(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to sign OAuth state", e);
    }
  }
}
