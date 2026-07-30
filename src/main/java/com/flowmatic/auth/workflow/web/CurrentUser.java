package com.flowmatic.auth.workflow.web;

import com.flowmatic.auth.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Resolves the id of the currently-authenticated user (JWT subject = email). */
@Component
public class CurrentUser {

  private final UserRepository userRepository;

  public CurrentUser(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public Long requireUserId(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw new IllegalStateException("No authenticated user");
    }
    String email = authentication.getName();
    return userRepository
        .findByEmail(email)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email))
        .getId();
  }
}
