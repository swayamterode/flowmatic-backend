package com.flowmatic.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final SecretKey signingKey;
  private final long accessTokenExpiryMs;
  private final long refreshTokenExpiryMs;

  public JwtUtil(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
      @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs) {
    this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
    this.accessTokenExpiryMs = accessTokenExpiryMs;
    this.refreshTokenExpiryMs = refreshTokenExpiryMs;
  }

  public String generateAccessToken(String email) {
    return buildToken(email, accessTokenExpiryMs, "access");
  }

  public String generateRefreshToken(String email) {
    return buildToken(email, refreshTokenExpiryMs, "refresh");
  }

  public long getAccessTokenExpirySeconds() {
    return accessTokenExpiryMs / 1000;
  }

  private String buildToken(String subject, long expiryMs, String tokenType) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expiryMs);

    return Jwts.builder()
        .subject(subject)
        .claim("type", tokenType)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(signingKey)
        .compact();
  }

  public String extractEmail(String token) {
    return parseClaims(token).getSubject();
  }

  public String extractTokenType(String token) {
    return parseClaims(token).get("type", String.class);
  }

  public boolean isTokenValid(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException ex) {
      return false;
    }
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
  }
}
