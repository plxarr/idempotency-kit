package io.github.plxarr.idempotency.exception;

/** Base exception for all errors that belong to the idempotency library. */
public abstract class IdempotencyException extends RuntimeException {

  /** @param message what went wrong, and for which key when there is one. */
  protected IdempotencyException(String message) {
    super(message);
  }

  /**
   * @param message what went wrong
   * @param cause the underlying failure
   */
  protected IdempotencyException(String message, Throwable cause) {
    super(message, cause);
  }
}
