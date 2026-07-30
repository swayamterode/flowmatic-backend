package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.workflow.entity.NodeType;
import java.util.Collections;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * Everything a {@link NodeExecutor} needs to run one node: its own config (from the graph JSON),
 * the merged output of its upstream node(s), and the owning user.
 */
@Getter
@Builder
public class NodeExecutionContext {

  private final String nodeId;
  private final NodeType nodeType;

  /** This node's configuration, taken from the React Flow node's {@code data}. Never null. */
  @Builder.Default private final Map<String, Object> config = Collections.emptyMap();

  /** Merged output of the direct upstream node(s). Null/empty for source nodes (e.g. TRIGGER). */
  @Builder.Default private final Map<String, Object> input = Collections.emptyMap();

  /**
   * All completed nodes' outputs, keyed by node id (the namespaced context for template/expression
   * resolution, e.g. {@code {{ai.summary}}}). Never null.
   */
  @Builder.Default private final Map<String, Object> context = Collections.emptyMap();

  /** Id of the user who owns the workflow run (used to resolve integrations, uploads, etc.). */
  private final Long userId;

  /** Convenience typed accessor for a config value. */
  public Object configValue(String key) {
    return config == null ? null : config.get(key);
  }

  public String configString(String key) {
    Object v = configValue(key);
    return v == null ? null : String.valueOf(v);
  }
}
