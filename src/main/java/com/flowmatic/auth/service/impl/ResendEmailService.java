package com.flowmatic.auth.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sends transactional email over Resend's HTTPS API. Render's free tier blocks outbound SMTP ports,
 * so this replaces {@code JavaMailSender} as the transport for every mail-sending path.
 */
@Service
public class ResendEmailService {

  private final RestClient restClient;
  private final String apiKey;
  private final String defaultFrom;

  public ResendEmailService(
      RestClient.Builder restClientBuilder,
      @Value("${resend.api.key}") String apiKey,
      @Value("${resend.from.email}") String defaultFrom) {
    this.restClient = restClientBuilder.baseUrl("https://api.resend.com").build();
    this.apiKey = apiKey;
    this.defaultFrom = defaultFrom;
  }

  /** Sends from the configured default address. Either {@code text} or {@code html} may be null. */
  public void send(String to, String subject, String text, String html) {
    send(defaultFrom, to, subject, text, html);
  }

  /** Sends from a caller-chosen address. Either {@code text} or {@code html} may be null. */
  public void send(String from, String to, String subject, String text, String html) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("from", from);
    payload.put("to", List.of(to));
    payload.put("subject", subject);
    if (text != null) {
      payload.put("text", text);
    }
    if (html != null) {
      payload.put("html", html);
    }

    restClient
        .post()
        .uri("/emails")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .toBodilessEntity();
  }
}
