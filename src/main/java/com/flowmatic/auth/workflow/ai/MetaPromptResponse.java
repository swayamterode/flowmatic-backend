package com.flowmatic.auth.workflow.ai;

/**
 * The rewritten prompt, ready to paste into an AI node's {@code prompt} config.
 *
 * @param prompt a short, self-contained instruction for a language model
 */
public record MetaPromptResponse(String prompt) {}
