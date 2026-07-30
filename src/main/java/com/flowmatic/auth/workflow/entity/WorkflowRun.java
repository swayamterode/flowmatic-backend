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
}
