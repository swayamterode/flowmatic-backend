package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
import com.flowmatic.auth.workflow.integration.DriveClient;
import com.flowmatic.auth.workflow.integration.UserIntegrationService;
import com.flowmatic.auth.workflow.upload.UploadStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DATA_SOURCE executor. Two source types produce the SAME {@code { "rows": [ {col: value}, ... ] }}
 * output:
 *
 * <ul>
 *   <li>Direct CSV upload — config {@code {"uploadId": "..."}} (original path, unchanged).
 *   <li>Google Drive — config {@code {"driveFileId": "..."}}. We look up the file's MIME type and
 *       either export a Google Sheet as CSV or download a CSV stored in Drive, then reuse the exact
 *       same {@link #parseCsv(Reader)} logic.
 * </ul>
 */
@Component
public class DataSourceNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(DataSourceNodeExecutor.class);

  /** Above this many rows we would pre-filter before handing data to the AI node (see below). */
  static final int PRE_FILTER_ROW_THRESHOLD = 500;

  private final UploadStorage uploadStorage;
  private final DriveClient driveClient;
  private final UserIntegrationService integrationService;

  public DataSourceNodeExecutor(
      UploadStorage uploadStorage,
      DriveClient driveClient,
      UserIntegrationService integrationService) {
    this.uploadStorage = uploadStorage;
    this.driveClient = driveClient;
    this.integrationService = integrationService;
  }

  @Override
  public NodeType supports() {
    return NodeType.DATA_SOURCE;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    String uploadId = context.configString("uploadId");
    String driveFileId = context.configString("driveFileId");

    if (uploadId != null && !uploadId.isBlank()) {
      return fromUpload(context, uploadId);
    }
    if (driveFileId != null && !driveFileId.isBlank()) {
      return fromDrive(context, driveFileId);
    }
    return NodeExecutionResult.failure("DATA_SOURCE node requires 'uploadId' or 'driveFileId'");
  }

  private NodeExecutionResult fromUpload(NodeExecutionContext context, String uploadId) {
    if (!uploadStorage.exists(uploadId)) {
      return NodeExecutionResult.failure("Uploaded file not found for uploadId=" + uploadId);
    }
    try (InputStream in = uploadStorage.open(uploadId);
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return rowsOutput(context, parseCsv(reader));
    } catch (IOException e) {
      return NodeExecutionResult.failure("Failed to read/parse CSV: " + e.getMessage());
    }
  }

  private NodeExecutionResult fromDrive(NodeExecutionContext context, String fileId) {
    if (context.getUserId() == null) {
      return NodeExecutionResult.failure("Drive source requires a user context");
    }
    try {
      String accessToken = integrationService.getValidAccessToken(context.getUserId());
      String mimeType = driveClient.getFileMimeType(fileId, accessToken);

      String csv;
      if (DriveClient.MIME_GOOGLE_SHEET.equals(mimeType)) {
        csv = driveClient.exportAsCsv(fileId, accessToken);
      } else if (DriveClient.MIME_CSV.equals(mimeType)) {
        csv = driveClient.downloadCsv(fileId, accessToken);
      } else {
        return NodeExecutionResult.failure("Unsupported Drive MIME type: " + mimeType);
      }
      return rowsOutput(context, parseCsv(new StringReader(csv)));
    } catch (IOException e) {
      return NodeExecutionResult.failure("Failed to parse Drive CSV: " + e.getMessage());
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("Drive source failed: " + e.getMessage());
    }
  }

  private NodeExecutionResult rowsOutput(
      NodeExecutionContext context, List<Map<String, String>> rows) {
    // SEAM: when a source has more than PRE_FILTER_ROW_THRESHOLD rows we should pre-filter here
    // (e.g. sampling / column pruning / relevance filtering) before passing everything to the AI
    // node, to control token cost. Not implemented yet — left as an explicit extension point.
    if (rows.size() > PRE_FILTER_ROW_THRESHOLD) {
      log.info(
          "DATA_SOURCE node {} produced {} rows (> {}); pre-filtering not yet implemented",
          context.getNodeId(),
          rows.size(),
          PRE_FILTER_ROW_THRESHOLD);
    }
    return NodeExecutionResult.success(Map.of("rows", rows));
  }

  /**
   * Parses CSV content (first record treated as the header) into a list of column->value maps.
   * Shared by both the upload and Drive paths so the two sources yield an identical row shape.
   */
  List<Map<String, String>> parseCsv(Reader reader) throws IOException {
    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    List<Map<String, String>> rows = new ArrayList<>();
    try (CSVParser parser = CSVParser.parse(reader, format)) {
      List<String> headers = parser.getHeaderNames();
      for (CSVRecord record : parser) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : headers) {
          row.put(header, record.get(header));
        }
        rows.add(row);
      }
    }
    return rows;
  }
}
