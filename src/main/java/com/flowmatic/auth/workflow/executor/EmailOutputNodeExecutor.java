package com.flowmatic.auth.workflow.executor;

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
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Generic OUTPUT (email) executor. {@code to}, {@code subject} and {@code body} are templates over
 * the namespaced context. With an optional {@code forEach} pointing at an upstream array, the node
 * sends once per element with {@code {{item.*}}} available; without it, it sends a single email.
 *
 * <p>Transport-agnostic (only {@link JavaMailSender} + a "from" address); one failed recipient
 * never aborts the batch.
 */
@Component
public class EmailOutputNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(EmailOutputNodeExecutor.class);
  private static final String DEFAULT_SUBJECT = "A message from Flowmatic";
  private static final String ITEM = "item";

  private final JavaMailSender mailSender;
  private final String defaultFrom;
  private final TemplateResolver templateResolver;

  public EmailOutputNodeExecutor(
      JavaMailSender mailSender,
      @Value("${app.mail.from}") String defaultFrom,
      TemplateResolver templateResolver) {
    this.mailSender = mailSender;
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

    int sent = 0;
    List<String> failed = new ArrayList<>();
    for (Map<String, Object> scope : scopes) {
      String to;
      SimpleMailMessage message;
      try {
        to = templateResolver.resolveToString(context.configValue("to"), scope);
        String subject = templateResolver.resolveToString(context.configValue("subject"), scope);
        String body = templateResolver.resolveToString(context.configValue("body"), scope);
        String from =
            context.configValue("from") == null
                ? defaultFrom
                : templateResolver.resolveToString(context.configValue("from"), scope);

        message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject);
        message.setText(body);
      } catch (RuntimeException e) {
        return NodeExecutionResult.failure("Email template error: " + e.getMessage());
      }

      try {
        mailSender.send(message);
        sent++;
      } catch (MailException e) {
        log.warn("Failed to send to {}: {}", to, e.getMessage());
        failed.add(to);
      }
    }

    log.info(
        "OUTPUT node {} sent {}/{} emails ({} failed)",
        context.getNodeId(),
        sent,
        scopes.size(),
        failed.size());
    return NodeExecutionResult.success(
        Map.of("sent", sent, "failed", failed, "total", scopes.size()));
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
}
