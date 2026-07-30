package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.WorkflowRun;
import com.flowmatic.auth.workflow.entity.WorkflowRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {

  List<WorkflowRun> findByWorkflow_IdOrderByStartedAtDesc(Long workflowId);

  /** Oldest queued run (FIFO by creation order) — the next one the drainer should execute. */
  Optional<WorkflowRun> findFirstByStatusOrderByIdAsc(WorkflowRunStatus status);
}
