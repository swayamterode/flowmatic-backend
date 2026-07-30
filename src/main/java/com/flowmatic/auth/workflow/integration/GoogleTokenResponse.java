package com.flowmatic.auth.workflow.integration;

/** Normalized token response from Google's OAuth2 token endpoint. */
public record GoogleTokenResponse(String accessToken, String refreshToken, long expiresInSeconds) {}
