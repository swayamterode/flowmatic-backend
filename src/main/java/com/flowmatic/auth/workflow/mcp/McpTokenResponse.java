package com.flowmatic.auth.workflow.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class McpTokenResponse {

  private String accessToken;
  private String tokenType;
}
