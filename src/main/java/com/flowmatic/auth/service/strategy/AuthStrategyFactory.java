package com.flowmatic.auth.service.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AuthStrategyFactory {

  private final Map<AuthProviderType, AuthenticationStrategy> strategies;

  public AuthStrategyFactory(List<AuthenticationStrategy> strategyBeans) {
    this.strategies =
        strategyBeans.stream()
            .collect(
                Collectors.toMap(AuthenticationStrategy::getProviderType, Function.identity()));
  }

  public AuthenticationStrategy getStrategy(AuthProviderType type) {
    AuthenticationStrategy strategy = strategies.get(type);
    if (strategy == null) {
      throw new IllegalArgumentException(
          "No authentication strategy registered for provider: " + type);
    }
    return strategy;
  }
}
