package com.flowmatic.auth.workflow.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionEvaluatorTest {

  private final ExpressionEvaluator eval = new ExpressionEvaluator();

  @Test
  void numericComparisonCoercesStrings() {
    // CSV row values are strings; "5" > 4 must work.
    Map<String, Object> row = Map.of("rating", "5");
    assertThat(eval.evaluate("rating > 4", row)).isTrue();
    assertThat(eval.evaluate("rating >= 5", row)).isTrue();
    assertThat(eval.evaluate("rating < 4", row)).isFalse();
    assertThat(eval.evaluate("rating == 5", row)).isTrue();
  }

  @Test
  void stringEqualityAndInequality() {
    Map<String, Object> ctx = Map.of("ai", Map.of("priority", "high"));
    assertThat(eval.evaluate("ai.priority == 'high'", ctx)).isTrue();
    assertThat(eval.evaluate("ai.priority != 'low'", ctx)).isTrue();
    assertThat(eval.evaluate("ai.priority == \"low\"", ctx)).isFalse();
  }

  @Test
  void andOrNotWithPrecedenceAndParens() {
    Map<String, Object> row = Map.of("rating", "5", "region", "US");
    assertThat(eval.evaluate("rating > 4 and region == 'US'", row)).isTrue();
    assertThat(eval.evaluate("rating > 4 and region == 'EU'", row)).isFalse();
    assertThat(eval.evaluate("rating > 9 or region == 'US'", row)).isTrue();
    assertThat(eval.evaluate("not rating > 9", row)).isTrue();
    assertThat(eval.evaluate("(rating > 9 or region == 'US') and not region == 'EU'", row))
        .isTrue();
  }

  @Test
  void containsOnStringAndList() {
    Map<String, Object> scope =
        Map.of("comment", "great support team", "tags", List.of("vip", "beta"));
    assertThat(eval.evaluate("comment contains 'support'", scope)).isTrue();
    assertThat(eval.evaluate("comment contains 'refund'", scope)).isFalse();
    assertThat(eval.evaluate("tags contains 'vip'", scope)).isTrue();
  }

  @Test
  void isEmptyAndIsNotEmpty() {
    Map<String, Object> scope = Map.of("rows", List.of(), "name", "Alice");
    assertThat(eval.evaluate("rows is empty", scope)).isTrue();
    assertThat(eval.evaluate("name is not empty", scope)).isTrue();
    assertThat(eval.evaluate("missing is empty", scope)).isTrue(); // missing path -> null -> empty
  }

  @Test
  void bareBooleanPath() {
    Map<String, Object> ctx = Map.of("c", Map.of("result", true));
    assertThat(eval.evaluate("c.result", ctx)).isTrue();
  }

  @Test
  void parseErrorsThrow() {
    assertThatThrownBy(() -> eval.evaluate("rating = 5", Map.of("rating", "5")))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> eval.evaluate("rating > ", Map.of("rating", "5")))
        .isInstanceOf(ExpressionException.class);
  }
}
