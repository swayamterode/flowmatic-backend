package com.flowmatic.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtUtilMcpTokenTest {

  private final JwtUtil jwtUtil =
      new JwtUtil(
          "0123456789012345678901234567890123456789", // dummy HS256 key, >= 32 bytes
          900_000L,
          604_800_000L,
          31_536_000_000L);

  @Test
  void generatesALongLivedMcpTypedToken() {
    String token = jwtUtil.generateMcpToken("mcp-user@example.com");

    assertThat(jwtUtil.isTokenValid(token)).isTrue();
    assertThat(jwtUtil.extractTokenType(token)).isEqualTo("mcp");
    assertThat(jwtUtil.extractEmail(token)).isEqualTo("mcp-user@example.com");
  }
}