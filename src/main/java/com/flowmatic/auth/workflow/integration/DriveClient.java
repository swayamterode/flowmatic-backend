package com.flowmatic.auth.workflow.integration;

/** Minimal Google Drive access used by the DATA_SOURCE node. */
public interface DriveClient {

  String MIME_GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet";
  String MIME_CSV = "text/csv";

  /** Returns the file's MIME type (e.g. {@link #MIME_GOOGLE_SHEET} or {@link #MIME_CSV}). */
  String getFileMimeType(String fileId, String accessToken);

  /** Exports a Google Sheet as CSV text via the Drive export endpoint. */
  String exportAsCsv(String fileId, String accessToken);

  /** Downloads the raw content of a non-Google file (e.g. a CSV stored in Drive). */
  String downloadCsv(String fileId, String accessToken);
}
