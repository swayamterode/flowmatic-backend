package com.flowmatic.auth.workflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.security.JwtUtil;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the MCP server over real HTTP — initialize, tools/list, tools/call — authenticated only by
 * an {@code mcp}-typed bearer token.
 *
 * <p>Deliberately does NOT use {@code @WithMockUser}: that would populate {@code
 * SecurityContextHolder} on the JUnit thread and mask the very thing under test. The tool methods
 * resolve their caller from that ThreadLocal, which only works because Spring AI applies {@code
 * immediateExecution(true)} for servlet SYNC servers, keeping tool invocation on the request thread
 * instead of hopping to {@code Schedulers.boundedElastic()}. If that ever changes, every tool
 * silently fails with "No authenticated user" — and only a test at this level catches it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpToolsCallOverHttpIntegrationTest {

  private static final String PROTOCOL_VERSION = "2025-06-18";
  private static final String SESSION_HEADER = "Mcp-Session-Id";

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;
  @Autowired JwtUtil jwtUtil;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void toolsAreRegisteredAndAToolCallResolvesTheCallerFromTheMcpToken() throws Exception {
    User owner = saveUser("mcp-http-owner@example.com");
    User other = saveUser("mcp-http-other@example.com");
    saveWorkflow(owner, "http-owner-workflow");
    saveWorkflow(other, "http-other-workflow");
    String mcpToken = jwtUtil.generateMcpToken("mcp-http-owner@example.com");

    String sessionId = initialize(mcpToken);

    // tools/list — proves the @McpTool annotation scan actually registered the bean's methods.
    JsonNode toolsList = rpc(mcpToken, sessionId, request(2, "tools/list", "{}"));
    List<String> toolNames = new ArrayList<>();
    toolsList.path("result").path("tools").forEach(t -> toolNames.add(t.path("name").asText()));
    assertThat(toolNames)
        .containsExactlyInAnyOrder(
            "list_workflows", "get_workflow", "run_workflow", "get_dashboard_summary");

    // tools/call — proves caller resolution works on the framework's invocation thread.
    JsonNode called =
        rpc(
            mcpToken,
            sessionId,
            request(3, "tools/call", "{\"name\":\"list_workflows\",\"arguments\":{}}"));

    JsonNode result = called.path("result");
    assertThat(result.path("isError").asBoolean(false))
        .as("tool call returned an error result: %s", result)
        .isFalse();

    String text = result.path("content").path(0).path("text").asText();
    assertThat(text).contains("http-owner-workflow").doesNotContain("http-other-workflow");
  }

  @Test
  void aToolErrorComesBackAsAnIsErrorResultRatherThanAStackTrace() throws Exception {
    saveUser("mcp-http-err@example.com");
    String mcpToken = jwtUtil.generateMcpToken("mcp-http-err@example.com");
    String sessionId = initialize(mcpToken);

    JsonNode called =
        rpc(
            mcpToken,
            sessionId,
            request(
                2,
                "tools/call",
                "{\"name\":\"get_workflow\",\"arguments\":{\"workflowId\":987654}}"));

    JsonNode result = called.path("result");
    assertThat(result.path("isError").asBoolean(false)).isTrue();
    String text = result.path("content").path(0).path("text").asText();
    assertThat(text).contains("Workflow not found");
    assertThat(text).doesNotContain("at com.flowmatic");
  }

  @Test
  void anAccessTokenIsAlsoAcceptedOnMcpButARefreshTokenIsNot() throws Exception {
    saveUser("mcp-http-types@example.com");

    // access tokens keep working everywhere, including /mcp — no regression for existing clients.
    String accessToken = jwtUtil.generateAccessToken("mcp-http-types@example.com");
    assertThat(initialize(accessToken)).isNotBlank();

    // refresh tokens are not API credentials and must be rejected here too.
    String refreshToken = jwtUtil.generateRefreshToken("mcp-http-types@example.com");
    MvcResult rejected =
        mockMvc.perform(mcpPost(refreshToken, null, initializeBody(1))).andReturn();
    assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
  }

  // ---- MCP plumbing -------------------------------------------------------

  private String initialize(String token) throws Exception {
    MvcResult result = mockMvc.perform(mcpPost(token, null, initializeBody(1))).andReturn();
    assertThat(result.getResponse().getStatus())
        .as("initialize failed: %s", result.getResponse().getContentAsString())
        .isEqualTo(200);

    String sessionId = result.getResponse().getHeader(SESSION_HEADER);
    assertThat(sessionId).as("server did not return an %s header", SESSION_HEADER).isNotBlank();

    // The spec requires this notification before normal operation.
    mockMvc
        .perform(
            mcpPost(
                token, sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .andReturn();
    return sessionId;
  }

  private JsonNode rpc(String token, String sessionId, String body) throws Exception {
    MvcResult result = mockMvc.perform(mcpPost(token, sessionId, body)).andReturn();
    assertThat(result.getResponse().getStatus())
        .as("request failed: %s", result.getResponse().getContentAsString())
        .isEqualTo(200);
    return payload(result);
  }

  private MockHttpServletRequestBuilder mcpPost(String token, String sessionId, String body) {
    MockHttpServletRequestBuilder builder =
        post("/mcp")
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json, text/event-stream")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (sessionId != null) {
      builder.header(SESSION_HEADER, sessionId);
    }
    return builder;
  }

  /** The response is either plain JSON or a single SSE frame; unwrap whichever came back. */
  private JsonNode payload(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    for (String line : body.split("\\R")) {
      if (line.startsWith("data:")) {
        return objectMapper.readTree(line.substring("data:".length()).trim());
      }
    }
    return objectMapper.readTree(body);
  }

  private static String initializeBody(int id) {
    return request(
        id,
        "initialize",
        "{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"integration-test\",\"version\":\"1.0\"}}");
  }

  private static String request(int id, String method, String params) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\""
        + method
        + "\",\"params\":"
        + params
        + "}";
  }

  // ---- fixtures -----------------------------------------------------------

  private User saveUser(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("MCP HTTP Test User")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow saveWorkflow(User user, String name) {
    return workflowRepository.save(
        Workflow.builder().user(user).name(name).graphJson("{\"nodes\":[],\"edges\":[]}").build());
  }
}
