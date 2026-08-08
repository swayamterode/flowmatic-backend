package com.flowmatic.auth.workflow.dashboard.dto;

/**
 * The dashboard's 4 KPI cards. Every {@code Double} field is nullable — {@code null} means
 * "undefined" (e.g. no completed runs to compute a rate/median from, or no baseline period to
 * compare against), never {@code NaN}/{@code Infinity}.
 */
public record SummaryStatsDTO(
    long executionsToday,
    Double executionsTodayDeltaPct,
    Double successRatePct,
    Double successRateDeltaPp,
    long failedRuns,
    Double failedRunsDeltaPct,
    Double medianRunTimeSeconds,
    Double medianRunTimeDeltaPct) {}
