package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.impl.ResendEmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpServerEndpointIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  @Test
  void mcpEndpointIsMappedAndReachableForAnAuthenticatedCaller() throws Exception {
    userRepository.save(
        User.builder()
            .email("mcp-endpoint@example.com")
            .fullName("Endpoint User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
    String accessToken = jwtUtil.generateAccessToken("mcp-endpoint@example.com");

    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();

    // Not asserting a specific 2xx/4xx code for the malformed JSON-RPC body — only that the
    // request reached a real handler instead of falling through to Spring's default 404.
    assertThat(result.getResponse().getStatus()).isNotEqualTo(404);
  }
}