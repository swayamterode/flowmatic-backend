package com.flowmatic.auth.workflow.dashboard.dto;

/** One point on the executions-over-time chart: a UTC calendar day and its run count. */
public record ExecutionRowDTO(String date, long executions) {}
