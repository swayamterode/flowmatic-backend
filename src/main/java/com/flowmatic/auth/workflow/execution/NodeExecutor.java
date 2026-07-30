package com.flowmatic.auth.workflow.execution;

import com.flowmatic.auth.workflow.entity.NodeType;

/**
 * Runs a single node of a workflow graph. There is exactly one implementation per {@link NodeType};
 * the execution engine dispatches to the right one via {@link #supports()}.
 */
public interface NodeExecutor {

  NodeType supports();

  NodeExecutionResult execute(NodeExecutionContext context);
}
