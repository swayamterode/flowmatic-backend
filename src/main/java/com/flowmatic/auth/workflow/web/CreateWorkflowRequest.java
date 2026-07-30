package com.flowmatic.auth.workflow.web;

import java.util.Map;

/**
 * Request body for creating/updating a workflow. {@code graph} is the React Flow graph as a JSON
 * object ({@code {"nodes": [...], "edges": [...]}}); it is stored verbatim in {@code graph_json}.
 */
public record CreateWorkflowRequest(String name, Map<String, Object> graph) {}
