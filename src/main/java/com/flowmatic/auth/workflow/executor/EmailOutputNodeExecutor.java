package com.flowmatic.auth.workflow.executor;

import com.flowmatic.auth.service.impl.ResendEmailService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Generic OUTPUT (email) executor. {@code to}, {@code subject} and {@code body} are templates over
 * the namespaced context. With an optional {@code forEach} pointing at an upstream array, the node
 * sends once per element with {@code {{item.*}}} available; without it, it sends a single email.
 *
 * <p>Transport-agnostic (only {@link ResendEmailService} + a "from" address); one failed recipient
 * never aborts the batch.
 *
 * <p>Alongside the {@code sent}/{@code failed}/{@code total} counters, the output records a {@code
 * messages} entry per attempt — the resolved recipient, subject and body, and whether it was sent —
 * so a finished run can show what actually went out rather than just how many. Bodies are capped;
 * see {@link #MAX_RECORDED_MESSAGES} and {@link #MAX_RECORDED_BODY}.
 *
 * <p>A node configured with {@code "sendMode": "manual"} resolves and records every message exactly
 * as above but never calls {@link ResendEmailService#send}, holding each entry {@code status:
 * "PENDING"} instead — the node still reports {@link NodeExecutionResult#success}, since resolving
 * the batch for review <em>is</em> its job in this mode. Pending entries are not truncated (they
 * still need to be sent later, so shortening a body here would mean silently sending a clipped
 * message). {@link #sendPending} is the follow-up step that actually sends them, called once a
 * human approves.
 */
@Component
public class EmailOutputNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(EmailOutputNodeExecutor.class);
  private static final String DEFAULT_SUBJECT = "A message from Flowmatic";
  private static final String ITEM = "item";

  /**
   * Caps on what goes into {@code node_run_logs.output_json}, which has no length limit of its own
   * — without these a 10,000-row upload would write every body to the run log. Both are reported in
   * the payload rather than truncating silently, and neither affects what is actually sent.
   */
  private static final int MAX_RECORDED_MESSAGES = 200;

  private static final int MAX_RECORDED_BODY = 2000;

  private static final String MANUAL = "manual";
  private static final String PENDING = "PENDING";
  private static final String SENT_STATUS = "SENT";
  private static final String FAILED_STATUS = "FAILED";

  private final ResendEmailService resendEmailService;
  private final String defaultFrom;
  private final TemplateResolver templateResolver;

  public EmailOutputNodeExecutor(
      ResendEmailService resendEmailService,
      @Value("${resend.from.email}") String defaultFrom,
      TemplateResolver templateResolver) {
    this.resendEmailService = resendEmailService;
    this.defaultFrom = defaultFrom;
    this.templateResolver = templateResolver;
  }

  @Override
  public NodeType supports() {
    return NodeType.OUTPUT;
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    if (context.configValue("to") == null) {
      return NodeExecutionResult.failure("Email node requires config 'to'");
    }
    if (context.configValue("body") == null) {
      return NodeExecutionResult.failure("Email node requires config 'body'");
    }

    List<Map<String, Object>> scopes;
    try {
      scopes = iterationScopes(context);
    } catch (RuntimeException e) {
      return NodeExecutionResult.failure("forEach error: " + e.getMessage());
    }

    boolean manual = MANUAL.equalsIgnoreCase(context.configString("sendMode"));

    int sent = 0;
    List<String> failed = new ArrayList<>();
    List<Map<String, Object>> messages = new ArrayList<>();
    for (Map<String, Object> scope : scopes) {
      ResolvedMessage message;
      try {
        String to = templateResolver.resolveToString(context.configValue("to"), scope);
        String subject = templateResolver.resolveToString(context.configValue("subject"), scope);
        String body = templateResolver.resolveToString(context.configValue("body"), scope);
        String from =
            context.configValue("from") == null
                ? defaultFrom
                : templateResolver.resolveToString(context.configValue("from"), scope);

        message =
            new ResolvedMessage(
                from, to, subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject, body);
      } catch (RuntimeException e) {
        return NodeExecutionResult.failure("Email template error: " + e.getMessage());
      }

      if (manual) {
        messages.add(pendingRecord(message));
        continue;
      }

      String error = null;
      try {
        resendEmailService.send(
            message.from(), message.to(), message.subject(), message.body(), null);
        sent++;
      } catch (RestClientException e) {
        log.warn("Failed to send to {}: {}", message.to(), e.getMessage());
        failed.add(message.to());
        error = e.getMessage();
      }

      if (messages.size() < MAX_RECORDED_MESSAGES) {
        messages.add(record(message, error));
      }
    }

    if (manual) {
      log.info(
          "OUTPUT node {} held {} messages for manual review", context.getNodeId(), scopes.size());
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("sendMode", MANUAL);
      output.put("sent", 0);
      output.put("failed", List.of());
      output.put("total", scopes.size());
      output.put("messages", messages);
      return NodeExecutionResult.success(output);
    }

    log.info(
        "OUTPUT node {} sent {}/{} emails ({} failed)",
        context.getNodeId(),
        sent,
        scopes.size(),
        failed.size());

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("sent", sent);
    output.put("failed", failed);
    output.put("total", scopes.size());
    output.put("messages", messages);
    if (scopes.size() > MAX_RECORDED_MESSAGES) {
      output.put("messagesTruncated", true);
    }
    return NodeExecutionResult.success(output);
  }

  /**
   * Actually sends every {@code PENDING} entry in a manual-mode node's recorded output, and returns
   * the updated output map to persist back over it. Called once, later, from outside the run that
   * produced {@code output} — not by the workflow engine.
   *
   * <p>Idempotent: an entry not in {@code PENDING} status (already sent by an earlier call) is left
   * untouched and simply recounted, so retrying after a partial failure — or a duplicate click —
   * only ever affects messages still awaiting a send.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> sendPending(Map<String, Object> output) {
    List<Map<String, Object>> pending = (List<Map<String, Object>>) output.get("messages");

    int sent = 0;
    List<String> failed = new ArrayList<>();
    List<Map<String, Object>> settled = new ArrayList<>();

    for (Map<String, Object> entry : pending) {
      if (!PENDING.equals(entry.get("status"))) {
        settled.add(entry);
        if (SENT_STATUS.equals(entry.get("status"))) {
          sent++;
        } else {
          failed.add(String.valueOf(entry.get("to")));
        }
        continue;
      }

      ResolvedMessage message =
          new ResolvedMessage(
              (String) entry.get("from"),
              (String) entry.get("to"),
              (String) entry.get("subject"),
              (String) entry.get("body"));

      String error = null;
      try {
        resendEmailService.send(
            message.from(), message.to(), message.subject(), message.body(), null);
        sent++;
      } catch (RestClientException e) {
        log.warn("Failed to send to {}: {}", message.to(), e.getMessage());
        failed.add(message.to());
        error = e.getMessage();
      }
      settled.add(record(message, error));
    }

    boolean truncated = settled.size() > MAX_RECORDED_MESSAGES;
    List<Map<String, Object>> capped =
        truncated ? settled.subList(0, MAX_RECORDED_MESSAGES) : settled;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sendMode", MANUAL);
    result.put("sent", sent);
    result.put("failed", failed);
    result.put("total", output.get("total"));
    result.put("messages", capped);
    if (truncated) {
      result.put("messagesTruncated", true);
    }
    return result;
  }

  /**
   * One run-log entry describing a message that was actually built and attempted. Read back off the
   * {@link ResolvedMessage} rather than the raw resolved strings, so what is recorded is exactly
   * what went to the transport — including the default subject substituted for a blank one.
   *
   * @param error the {@link RestClientException} message, or null if the send succeeded
   */
  private static Map<String, Object> record(ResolvedMessage message, String error) {
    Map<String, Object> entry = new LinkedHashMap<>();

    entry.put("to", message.to());
    entry.put("subject", message.subject());

    String body = message.body() == null ? "" : message.body();
    if (body.length() > MAX_RECORDED_BODY) {
      entry.put("body", body.substring(0, MAX_RECORDED_BODY));
      entry.put("bodyTruncated", true);
    } else {
      entry.put("body", body);
    }

    entry.put("status", error == null ? SENT_STATUS : FAILED_STATUS);
    if (error != null) {
      entry.put("error", error);
    }
    return entry;
  }

  /**
   * A message resolved for manual review, not yet sent. Unlike {@link #record}, the body is never
   * truncated and {@code from} is included — both are needed intact for {@link #sendPending} to
   * send this exact message later.
   */
  private static Map<String, Object> pendingRecord(ResolvedMessage message) {
    Map<String, Object> entry = new LinkedHashMap<>();

    entry.put("to", message.to());
    entry.put("subject", message.subject());
    entry.put("body", message.body() == null ? "" : message.body());
    entry.put("from", message.from());
    entry.put("status", PENDING);
    return entry;
  }

  /** One scope per iteration: forEach elements (with {@code item}), or a single context scope. */
  private List<Map<String, Object>> iterationScopes(NodeExecutionContext context) {
    Object forEachCfg = context.configValue("forEach");
    if (forEachCfg == null) {
      return List.of(context.getContext());
    }
    Object resolved = templateResolver.resolve(forEachCfg, context.getContext());
    if (!(resolved instanceof List<?> list)) {
      throw new IllegalArgumentException("forEach did not resolve to a list");
    }
    List<Map<String, Object>> scopes = new ArrayList<>();
    for (Object element : list) {
      Map<String, Object> scope = new LinkedHashMap<>(context.getContext());
      scope.put(ITEM, element);
      scopes.add(scope);
    }
    return scopes;
  }

  /**
   * A resolved email awaiting send or record — transport-agnostic replacement for
   * SimpleMailMessage.
   */
  private record ResolvedMessage(String from, String to, String subject, String body) {}
}
