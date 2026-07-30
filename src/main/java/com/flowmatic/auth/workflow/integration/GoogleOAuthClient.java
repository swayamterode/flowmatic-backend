package com.flowmatic.auth.workflow.integration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Thin client for Google's OAuth2 authorization-code + refresh flows. */
@Component
public class GoogleOAuthClient {

  private final GoogleIntegrationProperties props;
  private final RestClient restClient;

  public GoogleOAuthClient(GoogleIntegrationProperties props) {
    this.props = props;
    this.restClient = RestClient.create();
  }

  /**
   * Builds the consent URL. {@code access_type=offline} + {@code prompt=consent} yields a refresh
   * token.
   */
  public String authorizationUrl(String state) {
    return props.authUri()
        + "?client_id="
        + enc(props.clientId())
        + "&redirect_uri="
        + enc(props.redirectUri())
        + "&response_type=code&access_type=offline&prompt=consent&include_granted_scopes=true"
        + "&scope="
        + enc(props.scope())
        + "&state="
        + enc(state);
  }

  public GoogleTokenResponse exchangeCode(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    form.add("redirect_uri", props.redirectUri());
    return post(form);
  }

  public GoogleTokenResponse refresh(String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "refresh_token");
    form.add("refresh_token", refreshToken);
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    return post(form);
  }

  private GoogleTokenResponse post(MultiValueMap<String, String> form) {
    Map<?, ?> body =
        restClient
            .post()
            .uri(props.tokenUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    if (body == null) {
      throw new IllegalStateException("Empty token response from Google");
    }
    String accessToken = str(body.get("access_token"));
    String refreshToken = str(body.get("refresh_token")); // absent on refresh calls
    long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 0L;
    return new GoogleTokenResponse(accessToken, refreshToken, expiresIn);
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static String enc(String v) {
    return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
  }
}
