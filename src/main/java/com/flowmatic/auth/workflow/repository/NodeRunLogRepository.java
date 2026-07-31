package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.NodeRunLog;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeRunLogRepository extends JpaRepository<NodeRunLog, Long> {

  List<NodeRunLog> findByWorkflowRun_IdOrderByStartedAtAsc(Long workflowRunId);

  /** Bulk delete of every node log across all of a workflow's runs, in one statement. */
  @Modifying
  @Query(
      "delete from NodeRunLog l where l.workflowRun.id in "
          + "(select r.id from WorkflowRun r where r.workflow.id = :workflowId)")
  int deleteByWorkflowId(@Param("workflowId") Long workflowId);

  /**
   * Row-locked lookup for {@code sendPendingMessages} — holds a write lock for the rest of the
   * caller's transaction so two overlapping "Send" clicks on the same node run settle sequentially
   * instead of both reading PENDING and double-sending every message.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from NodeRunLog l where l.workflowRun.id = :runId and l.nodeId = :nodeId")
  Optional<NodeRunLog> findForUpdate(@Param("runId") Long runId, @Param("nodeId") String nodeId);
}
