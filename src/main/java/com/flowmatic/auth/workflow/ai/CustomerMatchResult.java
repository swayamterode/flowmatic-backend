package com.flowmatic.auth.workflow.ai;

import java.util.List;

/**
 * Structured output of the AI node: the matched customers plus a single outreach message template.
 * {@code messageBody} contains a literal {@code {{name}}} placeholder for later personalization by
 * the email node.
 */
public record CustomerMatchResult(List<CustomerMatch> customers, String messageBody) {}
