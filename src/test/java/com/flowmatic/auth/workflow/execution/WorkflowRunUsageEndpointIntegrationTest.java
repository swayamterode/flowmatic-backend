package com.flowmatic.auth.workflow.execution;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.Role;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.service.impl.ResendEmailService;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises {@code GET /api/workflows/runs/usage} and the 402 path on {@code POST /run}. Each test
 * uses its own user email — the lifetime run count persists across the whole test class (no
 * per-method rollback), so sharing one user would leak run counts between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowRunUsageEndpointIntegrationTest {

  @MockitoBean ResendEmailService resendEmailService;

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkflowRepository workflowRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private User seedOwner(String email) {
    return userRepository.save(
        User.builder()
            .email(email)
            .fullName("Owner")
            .passwordHash("x")
            .role(Role.USER)
            .emailVerified(true)
            .build());
  }

  private Workflow seedWorkflow(User owner) throws Exception {
    Map<String, Object> graph =
        Map.of(
            "nodes",
            List.of(Map.of("id", "t", "type", "TRIGGER", "data", Map.of())),
            "edges",
            List.of());
    return workflowRepository.save(
        Workflow.builder()
            .user(owner)
            .name("usage-endpoint-wf")
            .graphJson(objectMapper.writeValueAsString(graph))
            .build());
  }

  @Test
  @WithMockUser(username = "zero-used@example.com")
  void usageEndpointReturnsZeroUsedForNewUser() throws Exception {
    seedOwner("zero-used@example.com");

    mockMvc
        .perform(get("/api/workflows/runs/usage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.used").value(0))
        .andExpect(jsonPath("$.limit").value(10))
        .andExpect(jsonPath("$.remaining").value(10))
        .andExpect(jsonPath("$.unlimited").value(false));
  }

  @Test
  @WithMockUser(username = "null-plan@example.com")
  void usageEndpointReturnsNullPlanForAFreeTierUser() throws Exception {
    seedOwner("null-plan@example.com");

    mockMvc
        .perform(get("/api/workflows/runs/usage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").doesNotExist());
  }

  @Test
  @WithMockUser(username = "tracks-usage@example.com")
  void usageEndpointTracksRunsAsTheyAreEnqueued() throws Exception {
    Workflow workflow = seedWorkflow(seedOwner("tracks-usage@example.com"));

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(post("/api/workflows/{id}/run", workflow.getId()))
          .andExpect(status().isAccepted());
    }

    mockMvc
        .perform(get("/api/workflows/runs/usage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.used").value(3))
        .andExpect(jsonPath("$.remaining").value(7));
  }

  @Test
  @WithMockUser(username = "capped-endpoint@example.com")
  void runEndpointReturns402OnceCapIsHit() throws Exception {
    Workflow workflow = seedWorkflow(seedOwner("capped-endpoint@example.com"));

    for (int i = 0; i < 10; i++) {
      mockMvc
          .perform(post("/api/workflows/{id}/run", workflow.getId()))
          .andExpect(status().isAccepted());
    }

    mockMvc
        .perform(post("/api/workflows/{id}/run", workflow.getId()))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("Subscribe to continue")));
  }
}
