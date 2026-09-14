package com.flowmatic.nodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowmatic.workflow.entity.NodeType;
import com.flowmatic.nodes.NodeExecutionContext;
import com.flowmatic.nodes.NodeExecutionResult;
import com.flowmatic.workflow.integration.DriveClient;
import com.flowmatic.workflow.integration.UserIntegrationService;
import com.flowmatic.workflow.upload.UploadStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSourceNodeExecutorTest {

  /** In-memory {@link UploadStorage} that serves the classpath fixture for a fixed id. */
  private static final String FIXTURE_ID = "fixture";

  private final UploadStorage storage =
      new UploadStorage() {
        @Override
        public String store(String originalFilename, byte[] content) {
          return FIXTURE_ID;
        }

        @Override
        public InputStream open(String uploadId) {
          return getClass().getResourceAsStream("/fixtures/customers.csv");
        }

        @Override
        public boolean exists(String uploadId) {
          return FIXTURE_ID.equals(uploadId);
        }
      };

  // Drive collaborators are unused on the CSV-upload path; mock them.
  private final DataSourceNodeExecutor executor =
      new DataSourceNodeExecutor(
          storage, mock(DriveClient.class), mock(UserIntegrationService.class));

  @Test
  void parsesCsvIntoRows() {
    NodeExecutionContext context =
        NodeExecutionContext.builder()
            .nodeId("ds1")
            .nodeType(NodeType.DATA_SOURCE)
            .config(Map.of("uploadId", FIXTURE_ID))
            .build();

    NodeExecutionResult result = executor.execute(context);

    assertThat(result.isSuccess()).isTrue();
    @SuppressWarnings("unchecked")
    List<Map<String, String>> rows = (List<Map<String, String>>) result.getOutput().get("rows");

    assertThat(rows).hasSize(6);
    assertThat(rows.get(0))
        .containsEntry("name", "Alice Johnson")
        .containsEntry("email", "alice@example.com")
        .containsEntry("rating", "5")
        .containsEntry("comment", "Absolutely loved the product");
    assertThat(rows.get(5)).containsEntry("name", "Frank Miller").containsEntry("rating", "5");
  }

  @Test
  void failsWhenUploadIdMissing() {
    NodeExecutionContext context =
        NodeExecutionContext.builder().nodeId("ds1").nodeType(NodeType.DATA_SOURCE).build();

    NodeExecutionResult result = executor.execute(context);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("uploadId");
  }

  @Test
  void failsWhenFileMissing() {
    NodeExecutionContext context =
        NodeExecutionContext.builder()
            .nodeId("ds1")
            .nodeType(NodeType.DATA_SOURCE)
            .config(Map.of("uploadId", "does-not-exist"))
            .build();

    NodeExecutionResult result = executor.execute(context);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("not found");
  }

  @Test
  void parsesDirectlyFromReader() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/fixtures/customers.csv")) {
      List<Map<String, String>> rows =
          executor.parseCsv(
              new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
      assertThat(rows).hasSize(6);
    }
  }
}
