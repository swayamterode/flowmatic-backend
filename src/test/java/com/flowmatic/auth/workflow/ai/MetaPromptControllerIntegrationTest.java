package com.flowmatic.auth.workflow.ai;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract for the meta-prompt endpoint. The generation itself is covered by {@link
 * MetaPromptServiceTest}; here the service is stubbed so validation, status mapping, and auth can
 * be exercised without a model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetaPromptControllerIntegrationTest {

  private static final String CALLER = "prompter@example.com";

  @MockitoBean JavaMailSender mailSender;
  @MockitoBean MetaPromptService metaPromptService;

  @Autowired MockMvc mockMvc;

  private org.springframework.test.web.servlet.ResultActions postMessage(String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/ai/meta-prompt").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  @WithMockUser(username = CALLER)
  void returnsTheGeneratedPrompt() throws Exception {
    when(metaPromptService.generate("i want to email customers who havent paid"))
        .thenReturn("Write a polite payment reminder email for each unpaid customer.");

    postMessage("{\"message\":\"i want to email customers who havent paid\"}")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.prompt")
                .value("Write a polite payment reminder email for each unpaid customer."));
  }

  @Test
  @WithMockUser(username = CALLER)
  void missingMessageIsRejected() throws Exception {
    postMessage("{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message is required"));

    verify(metaPromptService, never()).generate(anyString());
  }

  @Test
  @WithMockUser(username = CALLER)
  void blankMessageIsRejected() throws Exception {
    postMessage("{\"message\":\"   \"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message is required"));

    verify(metaPromptService, never()).generate(anyString());
  }

  @Test
  @WithMockUser(username = CALLER)
  void overLongMessageIsRejected() throws Exception {
    String tooLong = "a".repeat(2001);

    postMessage("{\"message\":\"" + tooLong + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message must be at most 2000 characters"));

    verify(metaPromptService, never()).generate(anyString());
  }

  /** A model outage is an upstream fault, and its detail must not reach the caller. */
  @Test
  @WithMockUser(username = CALLER)
  void modelFailureBecomesBadGatewayWithoutLeakingDetail() throws Exception {
    when(metaPromptService.generate(anyString()))
        .thenThrow(
            new MetaPromptException(
                "Prompt generation failed", new IllegalStateException("groq 503 upstream")));

    postMessage("{\"message\":\"summarise my rows\"}")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.message").value("Prompt generation failed"))
        .andExpect(content().string(Matchers.not(Matchers.containsString("groq 503 upstream"))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("IllegalState"))));
  }

  @Test
  void requiresAuthentication() throws Exception {
    postMessage("{\"message\":\"summarise my rows\"}").andExpect(status().isUnauthorized());

    verify(metaPromptService, never()).generate(anyString());
  }
}
