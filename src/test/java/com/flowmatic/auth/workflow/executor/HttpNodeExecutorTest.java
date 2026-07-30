package com.flowmatic.auth.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpNodeExecutorTest {

  @Test
  void getReturnsStatusAndParsedJsonBody() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    HttpNodeExecutor executor = new HttpNodeExecutor(new TemplateResolver(), builder);

    server
        .expect(requestTo("https://api.example.com/items/42"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"ok\":true,\"n\":2}", MediaType.APPLICATION_JSON));

    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("h")
            .nodeType(NodeType.HTTP)
            .context(Map.of("ai", Map.of("id", 42)))
            .config(Map.of("method", "GET", "url", "https://api.example.com/items/{{ai.id}}"))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    server.verify();
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).containsEntry("status", 200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) result.getOutput().get("body");
    assertThat(body).containsEntry("ok", true);
  }

  @Test
  void postSendsTemplatedJsonBody() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    HttpNodeExecutor executor = new HttpNodeExecutor(new TemplateResolver(), builder);

    server
        .expect(requestTo("https://hooks.example.com/notify"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"received\":true}", MediaType.APPLICATION_JSON));

    NodeExecutionContext ctx =
        NodeExecutionContext.builder()
            .nodeId("h")
            .nodeType(NodeType.HTTP)
            .context(Map.of("ai", Map.of("summary", "all good")))
            .config(
                Map.of(
                    "method", "POST",
                    "url", "https://hooks.example.com/notify",
                    "body", Map.of("text", "{{ai.summary}}")))
            .build();

    NodeExecutionResult result = executor.execute(ctx);

    server.verify();
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).containsEntry("status", 200);
  }
}
