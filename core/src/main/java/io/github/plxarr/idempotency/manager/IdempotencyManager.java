package io.github.plxarr.idempotency.manager;

import io.github.plxarr.idempotency.concurrent.ConcurrentStrategy;
import io.github.plxarr.idempotency.exception.ExceptionHandlingStrategy;
import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import java.util.Objects;

/**
 * Encapsulates a storage backend ({@link IdempotencyStorage}) together with the default
 * behaviour values. The {@code @Idempotent} annotation can select a manager by name and
 * selectively override any of these defaults.
 *
 * <p><b>{@link Builder#storage(IdempotencyStorage) storage} is the only required setting.</b>
 * Every other value has a default, applied when you don't call its setter:
 *
 * <table border="1">
 *   <caption>Builder defaults</caption>
 *   <tr><th>Setting</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code storage}</td><td><i>none — required</i></td>
 *       <td>{@code build()} throws {@link NullPointerException} without it</td></tr>
 *   <tr><td>{@code defaultTtlMs}</td><td>{@code 300_000} (5 min)</td>
 *       <td>how long a result stays cached, and how long a crashed holder can block the key</td></tr>
 *   <tr><td>{@code defaultOnConcurrent}</td><td>{@code REJECT}</td>
 *       <td>a duplicate arriving mid-flight is turned away rather than made to wait</td></tr>
 *   <tr><td>{@code defaultConcurrentWaitTimeoutMs}</td><td>{@code 10_000}</td>
 *       <td>wait budget for {@code WAIT}</td></tr>
 *   <tr><td>{@code defaultExceptionStrategy}</td><td>caches nothing</td>
 *       <td>failures propagate and the key stays retryable</td></tr>
 * </table>
 *
 * <p>So this is a complete, valid manager:
 *
 * <pre>{@code
 * IdempotencyManager.builder().storage(new RedisIdempotencyStorage(redis)).build();
 * }</pre>
 */
public class IdempotencyManager {

  /** Fixed initial polling interval for the WAIT strategy. */
  public static final long INITIAL_INTERVAL_MS = 20;

  private final IdempotencyStorage storage;
  private final long defaultTtlMs;
  private final ConcurrentStrategy defaultOnConcurrent;
  private final long defaultConcurrentWaitTimeoutMs;
  private final ExceptionHandlingStrategy defaultExceptionStrategy;

  private IdempotencyManager(Builder b) {
    // The only check that runs unconditionally: every other setting has a default, so the
    // absence of a call to its setter is never an error.
    this.storage = Objects.requireNonNull(b.storage, "storage is required");
    this.defaultTtlMs = b.defaultTtlMs;
    this.defaultOnConcurrent = b.defaultOnConcurrent;
    this.defaultConcurrentWaitTimeoutMs = b.defaultConcurrentWaitTimeoutMs;
    this.defaultExceptionStrategy = b.defaultExceptionStrategy;
  }

  /** The backend results and locks live in for every method under this manager. */
  public IdempotencyStorage storage() {
    return storage;
  }

  /** Result TTL for annotations that leave {@code ttlMs} at {@code -1}. */
  public long defaultTtlMs() {
    return defaultTtlMs;
  }

  /** Duplicate-in-flight policy for annotations that leave {@code onConcurrent} at {@code UNSET}. */
  public ConcurrentStrategy defaultOnConcurrent() {
    return defaultOnConcurrent;
  }

  /** Wait budget for annotations that leave {@code concurrentWaitTimeoutMs} at {@code -1}. */
  public long defaultConcurrentWaitTimeoutMs() {
    return defaultConcurrentWaitTimeoutMs;
  }

  /** Error-caching strategy for annotations that don't name one. */
  public ExceptionHandlingStrategy defaultExceptionStrategy() {
    return defaultExceptionStrategy;
  }

  /**
   * Interval ceiling derived from the total wait budget: proportional to
   * {@code concurrentWaitTimeoutMs}, but never smaller than the initial interval. See the
   * backoff design discussion.
   */
  public static long maxIntervalFor(long concurrentWaitTimeoutMs) {
    return Math.max(INITIAL_INTERVAL_MS, concurrentWaitTimeoutMs / 10);
  }

  /** Starts a builder. Only {@link Builder#storage(IdempotencyStorage)} must be called. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builds an {@link IdempotencyManager}. Only {@link #storage(IdempotencyStorage)} has to be
   * called; see the table on {@link IdempotencyManager} for what each omitted setting falls
   * back to.
   */
  public static final class Builder {
    private IdempotencyStorage storage;
    private long defaultTtlMs = 300_000; // 5 min
    private ConcurrentStrategy defaultOnConcurrent = ConcurrentStrategy.REJECT;
    private long defaultConcurrentWaitTimeoutMs = 10_000;
    private ExceptionHandlingStrategy defaultExceptionStrategy =
        new ExceptionHandlingStrategy.Default();

    /** The backend. <b>Required:</b> {@code build()} throws without it. */
    public Builder storage(IdempotencyStorage storage) {
      this.storage = storage;
      return this;
    }

    /**
     * How long a cached result lives, in milliseconds. Defaults to 5 minutes.
     *
     * <p>The same value bounds the lock, so it is also the longest a key can stay blocked
     * after the process holding it dies. Long enough to be a useful cache, short enough to be
     * a tolerable outage for one key.
     */
    public Builder defaultTtlMs(long ttlMs) {
      this.defaultTtlMs = ttlMs;
      return this;
    }

    /** What a duplicate does while the first call is still running. Defaults to {@code REJECT}. */
    public Builder defaultOnConcurrent(ConcurrentStrategy strategy) {
      this.defaultOnConcurrent = strategy;
      return this;
    }

    /** How long {@code WAIT} polls before giving up, in milliseconds. Defaults to 10 seconds. */
    public Builder defaultConcurrentWaitTimeoutMs(long concurrentWaitTimeoutMs) {
      this.defaultConcurrentWaitTimeoutMs = concurrentWaitTimeoutMs;
      return this;
    }

    /**
     * Which failures get cached and replayed. Defaults to
     * {@link ExceptionHandlingStrategy.Default}, which caches none of them.
     */
    public Builder defaultExceptionStrategy(ExceptionHandlingStrategy strategy) {
      this.defaultExceptionStrategy = strategy;
      return this;
    }

    /**
     * @return the configured manager
     * @throws NullPointerException if no storage was set
     */
    public IdempotencyManager build() {
      return new IdempotencyManager(this);
    }
  }
}
