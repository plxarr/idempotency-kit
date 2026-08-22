package io.github.plxarr.idempotency.exception;

/**
 * Pluggable strategy that decides whether an error should be cached and how to
 * serialize/reconstruct it, so that repeated requests receive the same error without
 * re-executing the business logic.
 *
 * <p>For safety, the library never deserializes arbitrary types by class name. Each
 * application reconstructs its own exceptions by implementing this interface.
 */
public interface ExceptionHandlingStrategy {

  /**
   * Should this error be cached? If it returns {@code false}, the error propagates without
   * being memoized (the operation remains retryable).
   */
  boolean shouldCache(Throwable ex);

  /** Serializes the error to a storable String (e.g. the name of a reason enum). */
  String serialize(Throwable ex);

  /** Reconstructs the error from what was serialized. Rethrown on repeated calls. */
  Throwable deserialize(String cached);

  /**
   * Default strategy: never caches exceptions. It's the sentinel value used on the
   * {@code @Idempotent(onException = ...)} annotation to mean "use the manager's default".
   */
  final class Default implements ExceptionHandlingStrategy {
    @Override
    public boolean shouldCache(Throwable ex) {
      return false;
    }

    @Override
    public String serialize(Throwable ex) {
      throw new UnsupportedOperationException("Default strategy does not cache exceptions");
    }

    @Override
    public Throwable deserialize(String cached) {
      throw new UnsupportedOperationException("Default strategy does not cache exceptions");
    }
  }
}
