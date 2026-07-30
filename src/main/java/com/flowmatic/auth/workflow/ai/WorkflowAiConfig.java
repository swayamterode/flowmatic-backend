package com.flowmatic.auth.workflow.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the shared {@link ChatClient} from the auto-configured builder. */
@Configuration
public class WorkflowAiConfig {

  @Bean
  public ChatClient workflowChatClient(ChatClient.Builder builder) {
    return builder.build();
  }
}
