package com.flowmatic.auth.workflow.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.workflow.entity.NodeType;
import com.flowmatic.auth.workflow.execution.NodeExecutionContext;
import com.flowmatic.auth.workflow.execution.NodeExecutionResult;
import com.flowmatic.auth.workflow.execution.NodeExecutor;
import com.flowmatic.auth.workflow.expression.TemplateResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Generic HTTP executor. {@code url}, {@code headers} and {@code body} are templated over the
 * context (and {@code {{item.*}}} when {@code forEach} is set). Output is {@code {status, body}}
 * (body parsed as JSON when possible) for a single call, or {@code {responses, failures}} with
 * {@code forEach}. A non-2xx status is returned, not thrown.
 */
@Component
public class HttpNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(HttpNodeExecutor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ITEM = "item";

  private final TemplateResolver templateResolver;
  private final RestClient restClient;

  public HttpNodeExecutor(TemplateResolver templateResolver, RestClient.Builder restClientBuilder) {
    this.templateResolver = templateResolver;
    this.restClient = restClientBuilder.build();
  }

  @Override
  public NodeType supports() {
    return NodeType.HTTP;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    if (context.configValue("url") == null) {
      return NodeExecutionResult.failure("HTTP node requires config 'url'");
    }

    Object forEachCfg = context.configValue("forEach");
    if (forEachCfg == null) {
      try {
        return NodeExecutionResult.success(callOnce(context, context.getContext()));
      } catch (RuntimeException e) {
        return NodeExecutionResult.failure("HTTP request failed: " + e.getMessage());
      }
    }

    Object resolved = templateResolver.resolve(forEachCfg, context.getContext());
    if (!(resolved instanceof List<?> list)) {
      return NodeExecutionResult.failure("forEach did not resolve to a list");
    }
    List<Object> responses = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (Object element : list) {
      Map<String, Object> scope = new LinkedHashMap<>(context.getContext());
      scope.put(ITEM, element);
      try {
        responses.add(callOnce(context, scope));
      } catch (RuntimeException e) {
        log.warn("HTTP node {} item failed: {}", context.getNodeId(), e.getMessage());
        failures.add(e.getMessage());
      }
    }
    return NodeExecutionResult.success(Map.of("responses", responses, "failures", failures));
  }

  private Map<String, Object> callOnce(NodeExecutionContext context, Map<String, Object> scope) {
    String method =
        orDefault(templateResolver.resolveToString(context.configValue("method"), scope), "GET");
    String url = templateResolver.resolveToString(context.configValue("url"), scope);
    Object headers = templateResolver.resolve(context.configValue("headers"), scope);
    Object body = templateResolver.resolve(context.configValue("body"), scope);

    RestClient.RequestBodySpec spec =
        restClient.method(HttpMethod.valueOf(method.toUpperCase())).uri(url);
    if (headers instanceof Map<?, ?> headerMap) {
      spec.headers(h -> headerMap.forEach((k, v) -> h.add(String.valueOf(k), String.valueOf(v))));
    }
    if (body != null) {
      if (body instanceof Map || body instanceof List) {
        spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
      } else {
        spec = spec.body(body.toString());
      }
    }

    return spec.exchange(
        (request, response) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("status", response.getStatusCode().value());
          result.put("body", parseMaybeJson(response.bodyTo(String.class)));
          return result;
        });
  }

  private static Object parseMaybeJson(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    try {
      return MAPPER.readValue(text, Object.class);
    } catch (Exception e) {
      return text;
    }
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
