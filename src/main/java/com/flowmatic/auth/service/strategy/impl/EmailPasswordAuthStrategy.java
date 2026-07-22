package com.flowmatic.auth.service.strategy.impl;

import com.flowmatic.auth.dto.LoginRequest;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.exception.EmailNotVerifiedException;
import com.flowmatic.auth.exception.InvalidCredentialsException;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.strategy.AuthProviderType;
import com.flowmatic.auth.service.strategy.AuthenticatedUser;
import com.flowmatic.auth.service.strategy.AuthenticationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailPasswordAuthStrategy implements AuthenticationStrategy {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Override
  public AuthProviderType getProviderType() {
    return AuthProviderType.LOCAL;
  }

  @Override
  public AuthenticatedUser authenticate(Object credentials) {
    LoginRequest request = (LoginRequest) credentials;

    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

    if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
      throw new InvalidCredentialsException("Invalid email or password");
    }

    // Gate: a correct password is not enough — the email must be verified first.
    if (!user.isEmailVerified()) {
      throw new EmailNotVerifiedException(
          "Email not verified. Please verify your email before logging in.");
    }

    return AuthenticatedUser.builder()
        .email(user.getEmail())
        .fullName(user.getFullName())
        .newUser(false)
        .build();
  }
}
