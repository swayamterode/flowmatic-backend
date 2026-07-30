package com.flowmatic.auth.workflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the workflow run queue. Runs on Spring's single-threaded scheduler with a fixed delay, so
 * at most one drain pass is active at a time and runs execute strictly one-at-a-time. Each pass
 * processes every currently-queued run in FIFO order, then waits for the next tick.
 *
 * <p>Excluded under the {@code test} profile so integration tests can drive execution
 * deterministically.
 */
@Component
@Profile("!test")
public class WorkflowRunScheduler {

  private static final Logger log = LoggerFactory.getLogger(WorkflowRunScheduler.class);

  /** Safety cap so a flood of enqueues can't make one pass run unbounded. */
  private static final int MAX_PER_PASS = 500;

  private final WorkflowExecutionService executionService;

  public WorkflowRunScheduler(WorkflowExecutionService executionService) {
    this.executionService = executionService;
  }

  @Scheduled(fixedDelayString = "${app.workflow.poll-interval-ms:1000}")
  public void drainQueue() {
    int processed = 0;
    while (processed < MAX_PER_PASS && executionService.runNextPending()) {
      processed++;
    }
    if (processed > 0) {
      log.info("Workflow drainer processed {} run(s) this pass", processed);
    }
  }
}
