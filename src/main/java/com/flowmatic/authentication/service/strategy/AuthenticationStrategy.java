package com.flowmatic.authentication.service.strategy;

public interface AuthenticationStrategy {

  AuthProviderType getProviderType();

  AuthenticatedUser authenticate(Object credentials);
}
