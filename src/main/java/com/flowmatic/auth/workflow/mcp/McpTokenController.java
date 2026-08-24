package com.flowmatic.auth.workflow.mcp;

import com.flowmatic.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mints a long-lived personal access token for connecting an MCP client (e.g. Claude Desktop). */
@RestController
@RequestMapping("/api/mcp-token")
@RequiredArgsConstructor
public class McpTokenController {

  private final JwtUtil jwtUtil;

  @PostMapping
  public ResponseEntity<McpTokenResponse> generate(Authentication authentication) {
    String token = jwtUtil.generateMcpToken(authentication.getName());
    return ResponseEntity.ok(
        McpTokenResponse.builder().accessToken(token).tokenType("Bearer").build());
  }
}
