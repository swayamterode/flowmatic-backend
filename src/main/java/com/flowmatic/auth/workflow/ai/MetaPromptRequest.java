package com.flowmatic.auth.workflow.ai;

/**
 * A loose, conversational description of what the user wants an AI node to do.
 *
 * @param message e.g. "i want to email customers who havent paid"
 */
public record MetaPromptRequest(String message) {}
