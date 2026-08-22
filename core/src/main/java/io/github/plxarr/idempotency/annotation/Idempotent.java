package io.github.plxarr.idempotency.annotation;

import io.github.plxarr.idempotency.concurrent.ConcurrentStrategy;
import io.github.plxarr.idempotency.exception.ExceptionHandlingStrategy;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as idempotent. On repeated calls with the same key, the first one executes
 * and subsequent ones receive the cached result (or wait/reject if it's still in progress,
 * per {@link #onConcurrent()}).
 *
 * <p>Attributes with a sentinel value ({@code -1}, {@link ConcurrentStrategy#UNSET},
 * {@link ExceptionHandlingStrategy.Default}) inherit the default from the selected
 * {@code IdempotencyManager}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

  /**
   * SpEL expression evaluated over the method arguments to obtain the idempotency key.
   * Required. Ex: {@code "#request.email"}.
   */
  String key();

  /**
   * Name of the {@code IdempotencyManager} bean to use. If omitted, the single existing
   * manager is used, or the one marked {@code @Primary}. With several managers and none
   * primary, {@code IdempotencyConfigurationException} is thrown.
   */
  String manager() default "";

  /** Strategy for a duplicate in progress. {@code UNSET} inherits the manager's default. */
  ConcurrentStrategy onConcurrent() default ConcurrentStrategy.UNSET;

  /** Wait ceiling for {@link ConcurrentStrategy#WAIT}, in ms. {@code -1} inherits from the manager. */
  long concurrentWaitTimeoutMs() default -1;

  /**
   * Exception handling strategy (class resolved as a Spring bean).
   * {@link ExceptionHandlingStrategy.Default} inherits the manager's default.
   */
  Class<? extends ExceptionHandlingStrategy> onException() default ExceptionHandlingStrategy.Default.class;

  /** TTL of the cached result, in ms. {@code -1} inherits the manager's default. */
  long ttlMs() default -1;
}
