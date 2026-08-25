package com.flowmatic.auth.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmatic.auth.workflow.entity.ErrorCause;
import org.junit.jupiter.api.Test;

class WorkflowExecutionServiceClassifyErrorTest {

  @Test
  void classifiesNotConnectedMessageAsAuth() {
    assertThat(
            WorkflowExecutionService.classifyError(
                "Drive source failed: Google Drive is not connected"))
        .isEqualTo(ErrorCause.AUTH);
  }

  @Test
  void classifiesReconnectMessageAsAuth() {
    assertThat(
            WorkflowExecutionService.classifyError(
                "Drive source failed: Google access token expired and no refresh token is stored;"
                    + " reconnect required"))
        .isEqualTo(ErrorCause.AUTH);
  }

  @Test
  void classifiesTimeoutMessageAsTimeout() {
    assertThat(WorkflowExecutionService.classifyError("HTTP request failed: Read timed out"))
        .isEqualTo(ErrorCause.TIMEOUT);
  }

  @Test
  void classifiesOtherMessagesAsOther() {
    assertThat(WorkflowExecutionService.classifyError("Email node requires config 'to'"))
        .isEqualTo(ErrorCause.OTHER);
  }

  @Test
  void classifiesNullMessageAsOther() {
    assertThat(WorkflowExecutionService.classifyError(null)).isEqualTo(ErrorCause.OTHER);
  }
}
