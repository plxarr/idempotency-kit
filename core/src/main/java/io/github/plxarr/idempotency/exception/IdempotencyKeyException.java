package io.github.plxarr.idempotency.exception;

/** The idempotency key resolved to null or blank. Map to 400 Bad Request. */
public class IdempotencyKeyException extends IdempotencyException {
  /** @param message what went wrong, and for which key when there is one. */
  public IdempotencyKeyException(String message) {
    super(message);
  }
}
