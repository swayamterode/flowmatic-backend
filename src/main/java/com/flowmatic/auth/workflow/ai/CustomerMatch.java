package com.flowmatic.auth.workflow.ai;

/** A single customer the AI node selected, with the reason it matched the instruction. */
public record CustomerMatch(String name, String email, String reason) {}
