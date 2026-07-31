package com.flowmatic.auth.workflow.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a plain-language description into a prompt for a workflow AI node.
 *
 * <p>Requires a JWT: the path is absent from {@code SecurityConfig.PUBLIC_ENDPOINTS}, so the {@code
 * anyRequest().authenticated()} rule covers it. Anonymous access would let callers spend the
 * project's model quota.
 */
@RestController
@RequestMapping("/api/ai/meta-prompt")
public class MetaPromptController {

  private static final int MAX_MESSAGE_LENGTH = 2000;

  private final MetaPromptService metaPromptService;

  public MetaPromptController(MetaPromptService metaPromptService) {
    this.metaPromptService = metaPromptService;
  }

  @PostMapping
  public MetaPromptResponse generate(@RequestBody(required = false) MetaPromptRequest request) {
    String message = request == null ? null : request.message();
    if (message == null || message.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
    if (message.length() > MAX_MESSAGE_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "message must be at most " + MAX_MESSAGE_LENGTH + " characters");
    }

    try {
      return new MetaPromptResponse(metaPromptService.generate(message.trim()));
    } catch (MetaPromptException e) {
      // The cause is already logged in the service; the caller gets no upstream detail.
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prompt generation failed");
    }
  }
}
