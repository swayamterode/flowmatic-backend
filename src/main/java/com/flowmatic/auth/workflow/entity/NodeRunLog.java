package com.flowmatic.auth.workflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-node execution record within a {@link WorkflowRun}. This is the debugging surface: every
 * node's status, output and error is persisted here as the run progresses.
 */
@Entity
@Table(name = "node_run_logs")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NodeRunLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workflow_run_id", nullable = false)
  private WorkflowRun workflowRun;

  // React Flow's own node id (a string in the graph JSON), NOT a DB foreign key.
  @Column(name = "node_id", nullable = false)
  private String nodeId;

  // Stored as VARCHAR (not a native MySQL enum) so new node types never require a schema migration.
  @Enumerated(EnumType.STRING)
  @Column(name = "node_type", nullable = false, columnDefinition = "varchar(32)")
  private NodeType nodeType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "varchar(32)")
  private NodeRunStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_json")
  private String outputJson;

  @Column(name = "error_message", length = 2048)
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;
}
