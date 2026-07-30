package com.flowmatic.auth.workflow.execution;

import java.util.Collections;
import java.util.Map;
import lombok.Getter;

/**
 * Outcome of running one node. {@code output} is passed downstream to the next node and persisted
 * to {@code node_run_logs.output_json}. On failure, {@code errorMessage} (and optionally {@code
 * rawDetail}, e.g. a raw model response) is persisted to {@code node_run_logs.error_message}.
 */
@Getter
public class NodeExecutionResult {

  private final boolean success;
  private final Map<String, Object> output;
  private final String errorMessage;
  private final String rawDetail;

  private NodeExecutionResult(
      boolean success, Map<String, Object> output, String errorMessage, String rawDetail) {
    this.success = success;
    this.output = output == null ? Collections.emptyMap() : output;
    this.errorMessage = errorMessage;
    this.rawDetail = rawDetail;
  }

  public static NodeExecutionResult success(Map<String, Object> output) {
    return new NodeExecutionResult(true, output, null, null);
  }

  public static NodeExecutionResult failure(String errorMessage) {
    return new NodeExecutionResult(false, Collections.emptyMap(), errorMessage, null);
  }

  public static NodeExecutionResult failure(String errorMessage, String rawDetail) {
    return new NodeExecutionResult(false, Collections.emptyMap(), errorMessage, rawDetail);
  }
}
