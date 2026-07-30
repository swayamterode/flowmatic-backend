package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.integration.DriveClient;
import com.flowmatic.auth.workflow.integration.UserIntegrationService;
import com.flowmatic.auth.workflow.upload.UploadStorage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the Google Sheets export path yields the SAME {@code {"rows": [...]}} shape as CSV. */
class DataSourceDriveExportTest {

  private static final String SHEET_AS_CSV =
      """
      name,email,rating
      Alice Johnson,alice@example.com,5
      Bob Smith,bob@example.com,4
      """;

  private final UploadStorage storage = mock(UploadStorage.class);
  private final DriveClient driveClient = mock(DriveClient.class);
  private final UserIntegrationService integrationService = mock(UserIntegrationService.class);

  private final DataSourceNodeExecutor executor =
      new DataSourceNodeExecutor(storage, driveClient, integrationService);

  @Test
  void exportsGoogleSheetAndParsesIntoRows() {
    String fileId = "sheet-123";
    when(integrationService.getValidAccessToken(42L)).thenReturn("access-token");
    when(driveClient.getFileMimeType(eq(fileId), eq("access-token")))
        .thenReturn(DriveClient.MIME_GOOGLE_SHEET);
    when(driveClient.exportAsCsv(eq(fileId), eq("access-token"))).thenReturn(SHEET_AS_CSV);

    NodeExecutionContext context =
        NodeExecutionContext.builder()
            .nodeId("ds-drive")
            .nodeType(NodeType.DATA_SOURCE)
            .userId(42L)
            .config(Map.of("driveFileId", fileId))
            .build();

    NodeExecutionResult result = executor.execute(context);

    assertThat(result.isSuccess()).isTrue();
    @SuppressWarnings("unchecked")
    List<Map<String, String>> rows = (List<Map<String, String>>) result.getOutput().get("rows");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("name", "Alice Johnson")
        .containsEntry("email", "alice@example.com")
        .containsEntry("rating", "5");
    assertThat(rows.get(1)).containsEntry("name", "Bob Smith");
  }

  @Test
  void downloadsPlainCsvStoredInDrive() {
    String fileId = "csv-in-drive";
    when(integrationService.getValidAccessToken(42L)).thenReturn("tok");
    when(driveClient.getFileMimeType(fileId, "tok")).thenReturn(DriveClient.MIME_CSV);
    when(driveClient.downloadCsv(fileId, "tok")).thenReturn(SHEET_AS_CSV);

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("ds-drive")
                .nodeType(NodeType.DATA_SOURCE)
                .userId(42L)
                .config(Map.of("driveFileId", fileId))
                .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat((List<?>) result.getOutput().get("rows")).hasSize(2);
  }

  @Test
  void rejectsUnsupportedMimeType() {
    String fileId = "doc-1";
    when(integrationService.getValidAccessToken(42L)).thenReturn("tok");
    when(driveClient.getFileMimeType(fileId, "tok"))
        .thenReturn("application/vnd.google-apps.document");

    NodeExecutionResult result =
        executor.execute(
            NodeExecutionContext.builder()
                .nodeId("ds-drive")
                .nodeType(NodeType.DATA_SOURCE)
                .userId(42L)
                .config(Map.of("driveFileId", fileId))
                .build());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("Unsupported Drive MIME type");
  }
}
