package io.github.plxarr.idempotency.storage.caffeine;

import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Policy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Local (in-process) adapter based on Caffeine. Useful for single-node and tests.
 *
 * <p>Single-entry-per-key model that evolves state
 * ({@code PROCESSING} -> {@code RESULT:...} / {@code ERROR:...}), same as the Redis adapter.
 * The lock is the {@code PROCESSING} entry itself: taken with {@code putIfAbsent} and the
 * token is encoded as a prefix of the value so it can be released safely.
 *
 * <p>Every entry carries the TTL the aspect resolved for it — the {@code ttlMs} on the
 * annotation, or the manager's {@code defaultTtlMs} — the same way the Redis adapter does.
 * That is why this builds its own cache: per-entry expiry needs Caffeine's variable
 * expiration, and a {@link Cache} handed in from outside may not have it.
 *
 * <p><b>Limitation:</b> the lock is only exclusive within a single JVM. It doesn't provide
 * distributed exclusion across multiple instances; use Redis or a data grid with a native
 * lock for that.
 */
public class CaffeineIdempotencyStorage implements IdempotencyStorage {

  /** The PROCESSING value is stored as "PROCESSING:<token>" so it's released only with its own token. */
  private static final String PROCESSING = "PROCESSING";
  private static final String SEP = ":";

  /**
   * Every write sets its own expiry, so these only cover an entry written some other way.
   * {@code Long.MAX_VALUE} is Caffeine's "no expiry", and updates keep the remaining time.
   */
  private static final Expiry<String, String> PER_ENTRY_TTL =
      new Expiry<String, String>() {
        @Override
        public long expireAfterCreate(String key, String value, long currentTime) {
          return Long.MAX_VALUE;
        }

        @Override
        public long expireAfterUpdate(
            String key, String value, long currentTime, long currentDuration) {
          return currentDuration;
        }

        @Override
        public long expireAfterRead(
            String key, String value, long currentTime, long currentDuration) {
          return currentDuration;
        }
      };

  private final Cache<String, String> cache;
  private final Policy.VarExpiration<String, String> expiration;

  /** A cache with default sizing. */
  public CaffeineIdempotencyStorage() {
    this(Caffeine.newBuilder());
  }

  /**
   * @param builder configure sizing, stats or a removal listener on it; the expiry is set
   *     here, because per-entry TTLs depend on it
   */
  public CaffeineIdempotencyStorage(Caffeine<Object, Object> builder) {
    this.cache = builder.expireAfter(PER_ENTRY_TTL).build();
    this.expiration = cache.policy().expireVariably().orElseThrow();
  }

  /** The cache this storage built, for tests and metrics. */
  public Cache<String, String> cache() {
    return cache;
  }

  @Override
  public Optional<String> get(String key) {
    String value = cache.getIfPresent(key);
    if (value == null) {
      return Optional.empty();
    }
    // Normalizes the lock to "PROCESSING" for the aspect (hides the internal token).
    if (value.startsWith(PROCESSING + SEP)) {
      return Optional.of(PROCESSING);
    }
    return Optional.of(value);
  }

  @Override
  public void store(String key, String value, Duration ttl) {
    // Overwrites the state (PROCESSING -> RESULT:/ERROR:) on the same entry, and restarts
    // the TTL from this write, so a cached result lives its full ttl.
    expiration.put(key, value, ttl);
  }

  @Override
  public String acquireLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    String existing = expiration.putIfAbsent(key, PROCESSING + SEP + token, ttl);
    return existing == null ? token : null;
  }

  @Override
  public void releaseLock(String key, String token) {
    // Only releases if the entry is still OUR lock (same token) and not an already-written result.
    cache.asMap().remove(key, PROCESSING + SEP + token);
  }
}
