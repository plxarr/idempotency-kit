package io.github.plxarr.idempotency.testsupport;

import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double mirroring the real adapters: one entry per key that evolves
 * {@code PROCESSING -> RESULT:/ERROR:}, with the token embedded in the lock value so a
 * release only ever removes its own lock.
 *
 * <p>ConcurrentHashMap gives the same atomic {@code putIfAbsent} and conditional
 * {@code remove} that Caffeine exposes, so this exercises the real contract rather
 * than a stub of it. TTL is ignored: nothing here expires during a test.
 */
public class InMemoryIdempotencyStorage implements IdempotencyStorage {

  private static final String PROCESSING = "PROCESSING";
  private static final String SEP = ":";

  public final Map<String, String> map = new ConcurrentHashMap<>();

  @Override
  public Optional<String> get(String key) {
    String value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    return Optional.of(value.startsWith(PROCESSING + SEP) ? PROCESSING : value);
  }

  @Override
  public void store(String key, String value, Duration ttl) {
    map.put(key, value);
  }

  @Override
  public String acquireLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    return map.putIfAbsent(key, PROCESSING + SEP + token) == null ? token : null;
  }

  @Override
  public void releaseLock(String key, String token) {
    map.remove(key, PROCESSING + SEP + token);
  }

  /** Forces the key into the in-progress state, without running anything. */
  public void markProcessing(String key) {
    map.put(key, PROCESSING + SEP + UUID.randomUUID());
  }
}
