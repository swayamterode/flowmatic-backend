package com.flowmatic.auth.workflow.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code {{path}}} placeholders in node config values against a scope (the namespaced
 * context of prior node outputs, optionally augmented with {@code item} inside a forEach).
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Whole-value</b>: if a string is exactly one placeholder ({@code "{{ai.customers}}"}),
 *       the RAW referenced object is returned (List/Map/Number/...), so arrays and objects flow
 *       through intact (this is what makes {@code forEach} work).
 *   <li><b>Embedded</b>: otherwise each {@code {{...}}} is replaced by its stringified value
 *       (scalars via toString, objects/arrays as compact JSON) and the concatenated string
 *       returned.
 * </ul>
 *
 * <p>Maps and lists are resolved recursively. A placeholder that references missing data throws
 * {@link TemplateResolutionException} so typos surface instead of silently producing blanks.
 */
@Component
public class TemplateResolver {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");
  private static final Pattern WHOLE = Pattern.compile("^\\{\\{\\s*([^}]+?)\\s*}}$");

  /** Resolve a config value (String/Map/List/scalar) recursively against {@code scope}. */
  @SuppressWarnings("unchecked")
  public Object resolve(Object value, Map<String, Object> scope) {
    if (value instanceof String s) {
      return resolveString(s, scope);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        out.put(String.valueOf(e.getKey()), resolve(e.getValue(), scope));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(v -> resolve(v, scope)).toList();
    }
    return value;
  }

  /** Convenience for config values expected to be strings. */
  public String resolveToString(Object value, Map<String, Object> scope) {
    Object resolved = resolve(value, scope);
    return resolved == null ? null : stringify(resolved);
  }

  private Object resolveString(String s, Map<String, Object> scope) {
    Matcher whole = WHOLE.matcher(s.trim());
    if (whole.matches()) {
      return lookup(whole.group(1).trim(), scope);
    }
    Matcher m = PLACEHOLDER.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      Object value = lookup(m.group(1).trim(), scope);
      m.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private Object lookup(String path, Map<String, Object> scope) {
    return PathAccessor.resolve(scope, path)
        .orElseThrow(() -> new TemplateResolutionException("unknown reference: {{" + path + "}}"));
  }

  private String stringify(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }
}
