package com.flowmatic.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {

  private String accessToken;
  private String refreshToken;
  private String tokenType;
  private long expiresInSeconds;
  private String email;
  private String fullName;
}
