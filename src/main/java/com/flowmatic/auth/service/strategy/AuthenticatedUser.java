package com.flowmatic.auth.service.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class AuthenticatedUser {

  private String email;
  private String fullName;
  private boolean newUser;
}
