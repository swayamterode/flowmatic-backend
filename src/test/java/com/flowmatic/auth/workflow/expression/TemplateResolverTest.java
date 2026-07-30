package com.flowmatic.auth.workflow.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateResolverTest {

  private final TemplateResolver resolver = new TemplateResolver();

  private Map<String, Object> scope() {
    return Map.of(
        "ds", Map.of("rows", List.of(Map.of("name", "Alice"), Map.of("name", "Bob"))),
        "ai", Map.of("summary", "all good", "priority", "high", "count", 3),
        "item", Map.of("email", "alice@example.com", "name", "Alice"));
  }

  @Test
  void wholeValuePlaceholderReturnsRawObject() {
    Object rows = resolver.resolve("{{ds.rows}}", scope());
    assertThat(rows).isInstanceOf(List.class);
    assertThat((List<?>) rows).hasSize(2);
  }

  @Test
  void embeddedPlaceholdersAreStringified() {
    Object v = resolver.resolve("Priority is {{ai.priority}} ({{ai.count}})", scope());
    assertThat(v).isEqualTo("Priority is high (3)");
  }

  @Test
  void dottedAndIndexedPaths() {
    assertThat(resolver.resolve("{{ds.rows.0.name}}", scope())).isEqualTo("Alice");
    assertThat(resolver.resolve("{{ds.rows.1.name}}", scope())).isEqualTo("Bob");
  }

  @Test
  void itemScopeUsedInForEach() {
    assertThat(resolver.resolve("Hi {{item.name}} <{{item.email}}>", scope()))
        .isEqualTo("Hi Alice <alice@example.com>");
  }

  @Test
  void resolvesRecursivelyThroughMapsAndLists() {
    Object cfg =
        resolver.resolve(
            Map.of("to", "{{item.email}}", "tags", List.of("{{ai.priority}}", "static")), scope());
    assertThat(cfg).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) cfg;
    assertThat(m.get("to")).isEqualTo("alice@example.com");
    @SuppressWarnings("unchecked")
    List<String> tags = (List<String>) m.get("tags");
    assertThat(tags).containsExactly("high", "static");
  }

  @Test
  void missingReferenceThrows() {
    assertThatThrownBy(() -> resolver.resolve("{{ai.nope}}", scope()))
        .isInstanceOf(TemplateResolutionException.class)
        .hasMessageContaining("ai.nope");
  }

  @Test
  void plainStringUnchanged() {
    assertThat(resolver.resolve("no placeholders here", scope())).isEqualTo("no placeholders here");
  }
}
