package com.flowmatic.auth.workflow.entity;

import com.flowmatic.auth.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** A workflow definition owned by a user. The React Flow graph is stored verbatim as JSON. */
@Entity
@Table(name = "workflows")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Workflow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false)
  private String name;

  // React Flow graph (nodes + edges) stored as a JSON column. The JSON SQL type is resolved
  // per-dialect (MySQL -> json), so no dialect-specific columnDefinition is needed.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "graph_json", nullable = false)
  private String graphJson;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
