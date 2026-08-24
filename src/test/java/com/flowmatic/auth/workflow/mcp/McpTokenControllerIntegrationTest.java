package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.impl.ResendEmailService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpTokenControllerIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @WithMockUser(username = "mcp-token-owner@example.com")
  void mintsAnMcpTypedTokenForTheLoggedInUser() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-token-owner@example.com")
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());

    String body =
        mockMvc
            .perform(post("/api/mcp-token"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
    assertThat(parsed.get("tokenType")).isEqualTo("Bearer");
    String token = (String) parsed.get("accessToken");
    assertThat(jwtUtil.extractTokenType(token)).isEqualTo("mcp");
    assertThat(jwtUtil.extractEmail(token)).isEqualTo("mcp-token-owner@example.com");
  }

  @Test
  void mcpTokenWorksOnMcpEndpointButNotOnRegularApiRoutes() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-scope@example.com")
            .fullName("Scope User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
    String mcpToken = jwtUtil.generateMcpToken("mcp-scope@example.com");

    MvcResult onMcpEndpoint =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + mcpToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();
    assertThat(onMcpEndpoint.getResponse().getStatus()).isNotEqualTo(401);

    mockMvc
        .perform(get("/api/workflows").header("Authorization", "Bearer " + mcpToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void mcpTokenIsRejectedOnAPathThatMerelyStartsWithMcp() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-boundary@example.com")
            .fullName("Boundary User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
    String mcpToken = jwtUtil.generateMcpToken("mcp-boundary@example.com");

    // /mcp-admin doesn't exist in this app, but it starts with the literal characters "/mcp" —
    // a raw prefix match would wrongly treat it as in-scope for this token type.
    mockMvc
        .perform(get("/mcp-admin").header("Authorization", "Bearer " + mcpToken))
        .andExpect(status().isUnauthorized());
  }
}
