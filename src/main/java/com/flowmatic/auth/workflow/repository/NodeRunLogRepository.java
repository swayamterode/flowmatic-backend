package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.NodeRunLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRunLogRepository extends JpaRepository<NodeRunLog, Long> {

  List<NodeRunLog> findByWorkflowRun_IdOrderByStartedAtAsc(Long workflowRunId);
}
