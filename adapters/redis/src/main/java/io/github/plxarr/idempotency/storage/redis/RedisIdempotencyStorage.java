package io.github.plxarr.idempotency.storage.redis;

import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Adapter for Redis and RESP-protocol-compatible backends
 * (Valkey, KeyDB, Garnet, Dragonfly, DiceDB). Offers a real distributed lock via
 * {@code SET NX PX} and token-aware release through an atomic Lua script.
 *
 * <p>Single-entry-per-key model that evolves state
 * ({@code PROCESSING} -> {@code RESULT:...} / {@code ERROR:...}). The lock is the
 * {@code PROCESSING} entry itself; the token is encoded in the value to release only
 * its own lock. The TTL is unified for both the {@code PROCESSING} lock and the result,
 * same as in Caffeine.
 */
public class RedisIdempotencyStorage implements IdempotencyStorage {

  private static final String PROCESSING = "PROCESSING";
  private static final String SEP = ":";

  /** Releases only if the entry is still exactly our lock (PROCESSING:token). */
  private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] "
              + "then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;

  /**
   * @param redis the template the lock and the cache entries run on; both its key and value
   *     serializers must be String-based
   */
  public RedisIdempotencyStorage(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> get(String key) {
    String value = redis.opsForValue().get(key);
    if (value == null) {
      return Optional.empty();
    }
    // Normalizes the lock to "PROCESSING" (hides the internal token) for the aspect.
    if (value.startsWith(PROCESSING + SEP)) {
      return Optional.of(PROCESSING);
    }
    return Optional.of(value);
  }

  @Override
  public void store(String key, String value, Duration ttl) {
    redis.opsForValue().set(key, value, ttl);
  }

  @Override
  public String acquireLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    Boolean ok = redis.opsForValue().setIfAbsent(key, PROCESSING + SEP + token, ttl);
    return Boolean.TRUE.equals(ok) ? token : null;
  }

  @Override
  public void releaseLock(String key, String token) {
    redis.execute(RELEASE_SCRIPT, List.of(key), PROCESSING + SEP + token);
  }
}
