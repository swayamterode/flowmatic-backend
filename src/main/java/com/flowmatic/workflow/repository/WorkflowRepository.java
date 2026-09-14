package com.flowmatic.workflow.repository;

import com.flowmatic.workflow.entity.Workflow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

  List<Workflow> findByUser_Id(Long userId);
}
