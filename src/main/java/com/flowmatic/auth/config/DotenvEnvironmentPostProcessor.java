package com.flowmatic.auth.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads {@code .env} (if present) as a low-priority property source, purely for local
 * development. Real environment variables — as injected by Render/Docker in deployed
 * environments, where no .env file exists — always take precedence.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    Path envFile = Path.of(".env");
    if (!Files.isRegularFile(envFile)) {
      return;
    }

    Map<String, Object> values = new LinkedHashMap<>();
    try {
      for (String line : Files.readAllLines(envFile)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
          continue;
        }
        String key = trimmed.substring(0, separator).trim();
        String value = stripQuotes(trimmed.substring(separator + 1).trim());
        values.put(key, value);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read .env file", e);
    }

    if (!values.isEmpty()) {
      environment.getPropertySources().addLast(new MapPropertySource("dotenv", values));
    }
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }
}