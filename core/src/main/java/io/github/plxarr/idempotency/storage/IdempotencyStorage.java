package io.github.plxarr.idempotency.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage abstraction the library uses for two responsibilities: caching results/errors
 * and coordinating the distributed lock.
 *
 * <p>The provided adapters (Redis, Caffeine) implement this interface. Any backend
 * with atomic compare-and-set and TTL expiration can implement it.
 *
 * <h3>Contract for implementers</h3>
 * <ul>
 *   <li>{@link #acquireLock} must be atomic: return a unique token if it takes the lock,
 *       or {@code null} if it's already taken. The lock MUST expire after {@code ttl} to
 *       tolerate crashes of the node that took it.</li>
 *   <li>{@link #releaseLock} must be token-aware: release only if the token matches, so as
 *       not to release a lock that now belongs to another request.</li>
 *   <li>{@link #store} must persist with automatic expiration.</li>
 *   <li>{@link #get} returns {@link Optional#empty()} if there's no value (or it expired).</li>
 * </ul>
 *
 * <p><b>Note on distributed exclusion:</b> local backends (Caffeine) only guarantee
 * intra-JVM atomicity. For exclusion across multiple instances use Redis (or compatible)
 * or a data grid with a native lock (Hazelcast, Ignite).
 */
public interface IdempotencyStorage {

  /** Reads a cached value (by convention {@code RESULT:...} or {@code ERROR:...}). */
  Optional<String> get(String key);

  /** Stores a value with expiration. */
  void store(String key, String value, Duration ttl);

  /**
   * Attempts to atomically acquire the lock.
   *
   * @return a unique token if it acquired it, or {@code null} if it was already taken.
   */
  String acquireLock(String key, Duration ttl);

  /** Releases the lock only if the token matches the stored one. */
  void releaseLock(String key, String token);
}
