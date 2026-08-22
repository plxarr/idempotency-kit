package io.github.plxarr.idempotency.exception;

/**
 * There's an operation in progress with the same key. Thrown immediately with REJECT,
 * or with WAIT once the max wait time runs out. Map to 409 Conflict.
 */
public class IdempotencyConflictException extends IdempotencyException {
  /** @param message what went wrong, and for which key when there is one. */
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
