package io.github.plxarr.idempotency.exception;

/** Serialization or deserialization of a cached result failed. */
public class IdempotencySerializationException extends IdempotencyException {
  /**
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public IdempotencySerializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
