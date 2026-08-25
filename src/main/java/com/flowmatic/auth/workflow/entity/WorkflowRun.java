package com.flowmatic.auth.workflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single execution of a {@link Workflow}. */
@Entity
@Table(name = "workflow_runs")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workflow_id", nullable = false)
  private Workflow workflow;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "varchar(32)")
  private WorkflowRunStatus status;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  // Nullable at the DB level even though every new run sets one of these: adding a NOT NULL column
  // via Hibernate ddl-auto=update fails against an already-populated table. Legacy rows read back
  // null and are treated as "predates this field", not an error.
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", columnDefinition = "varchar(32)")
  private TriggerType triggerType;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_cause", columnDefinition = "varchar(32)")
  private ErrorCause errorCause;
}
