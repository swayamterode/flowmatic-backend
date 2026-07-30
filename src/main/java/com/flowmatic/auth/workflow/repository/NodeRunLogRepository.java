package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.NodeRunLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
