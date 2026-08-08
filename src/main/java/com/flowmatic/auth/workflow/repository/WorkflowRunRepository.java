package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {

  List<WorkflowRun> findByWorkflow_IdOrderByStartedAtDesc(Long workflowId);

  /** Oldest queued run (FIFO by creation order) — the next one the drainer should execute. */
  Optional<WorkflowRun> findFirstByStatusOrderByIdAsc(WorkflowRunStatus status);

  /** True while the drainer may still be writing to this workflow's runs. */
  boolean existsByWorkflow_IdAndStatusIn(Long workflowId, Collection<WorkflowRunStatus> statuses);

  /** Bulk delete — avoids loading the whole run history just to remove it. */
  @Modifying
  @Query("delete from WorkflowRun r where r.workflow.id = :workflowId")
  int deleteByWorkflowId(@Param("workflowId") Long workflowId);

  /** A user's runs that have started, at or after {@code since} — for day-bucketing dashboards. */
  List<WorkflowRun> findByWorkflow_User_IdAndStartedAtGreaterThanEqual(Long userId, Instant since);
}
