package com.flowmatic.auth.workflow.expression;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves dotted paths (with array indexing) against a scope, e.g. {@code ai.customers.0.email}.
 * Shared by {@link TemplateResolver} and {@link ExpressionEvaluator} so both navigate data the same
 * way.
 */
public final class PathAccessor {

  private PathAccessor() {}

  /** Returns the value at {@code path}, or empty if any segment is missing/out of range. */
  public static Optional<Object> resolve(Object root, String path) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    Object current = root;
    for (String segment : path.split("\\.")) {
      if (current == null) {
        return Optional.empty();
      }
      if (current instanceof Map<?, ?> map) {
        if (!map.containsKey(segment)) {
          return Optional.empty();
        }
        current = map.get(segment);
      } else if (current instanceof List<?> list) {
        Integer index = tryParseInt(segment);
        if (index == null || index < 0 || index >= list.size()) {
          return Optional.empty();
        }
        current = list.get(index);
      } else {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(current);
  }

  private static Integer tryParseInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
