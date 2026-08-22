package io.github.plxarr.idempotency.exception;

/** Invalid annotation or context configuration (e.g. ambiguous manager). */
public class IdempotencyConfigurationException extends IdempotencyException {
  /** @param message what went wrong, and for which key when there is one. */
  public IdempotencyConfigurationException(String message) {
    super(message);
  }
}
