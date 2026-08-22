package io.github.plxarr.idempotency.concurrent;

/**
 * What to do when a duplicate request arrives while another one with the same key is
 * still executing (PROCESSING state).
 */
public enum ConcurrentStrategy {

  /** Sentinel value: the annotation didn't specify it, use the manager's default. */
  UNSET,

  /** Reject immediately by throwing {@code IdempotencyConflictException}. */
  REJECT,

  /**
   * Wait (polling with exponential backoff) for the first request to finish and return
   * its result / rethrow its error. When the max wait time runs out, throws
   * {@code IdempotencyConflictException}.
   */
  WAIT
}
