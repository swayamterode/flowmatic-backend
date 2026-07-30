package com.flowmatic.auth.workflow.integration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link DriveClient} backed by the Google Drive v3 REST API. */
@Component
public class GoogleDriveClient implements DriveClient {

  private static final String BASE = "https://www.googleapis.com/drive/v3/files/";

  private final RestClient restClient = RestClient.create();

  @Override
  public String getFileMimeType(String fileId, String accessToken) {
    Map<?, ?> body =
        restClient
            .get()
            .uri(BASE + enc(fileId) + "?fields=mimeType&supportsAllDrives=true")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(Map.class);
    return body == null ? null : String.valueOf(body.get("mimeType"));
  }

  @Override
  public String exportAsCsv(String fileId, String accessToken) {
    return restClient
        .get()
        .uri(BASE + enc(fileId) + "/export?mimeType=" + enc(MIME_CSV))
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(String.class);
  }

  @Override
  public String downloadCsv(String fileId, String accessToken) {
    return restClient
        .get()
        .uri(BASE + enc(fileId) + "?alt=media&supportsAllDrives=true")
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(String.class);
  }

  private static String enc(String v) {
    return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
  }
}
