package com.flowmatic.auth.workflow.integration;

/** Raised when a workflow needs an integration (e.g. Google Drive) the user hasn't connected. */
public class IntegrationNotConnectedException extends RuntimeException {
  public IntegrationNotConnectedException(String message) {
    super(message);
  }
}
