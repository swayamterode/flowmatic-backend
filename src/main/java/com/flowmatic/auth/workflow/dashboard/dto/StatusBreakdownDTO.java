package com.flowmatic.auth.workflow.dashboard.dto;

/** One slice of the dashboard's executions-by-status pie chart. */
public record StatusBreakdownDTO(String status, long count) {}
