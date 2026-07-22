package com.flowmatic.auth.service.strategy;

public interface AuthenticationStrategy {

  AuthProviderType getProviderType();

  AuthenticatedUser authenticate(Object credentials);
}
