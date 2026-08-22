package io.github.plxarr.idempotency.storage.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Exercises the Redis adapter against a real server. Two things here can't be covered in
 * plain Java: the {@code SET NX PX} lock and the Lua release script, which is the only part
 * that makes releasing token-aware.
 *
 * <p>Skips itself when nothing is listening, so a plain {@code mvn test} stays green without
 * Redis. Point it elsewhere with {@code -Didempotency.redis.host} / {@code .port}; the default
 * port is deliberately not 6379, to stay clear of whatever a developer already has running.
 *
 * <pre>{@code
 * docker run -d --name idempotency-kit-redis -p 6380:6379 redis:7-alpine
 * }</pre>
 */
class RedisIdempotencyStorageIT {

  private static final String HOST = System.getProperty("idempotency.redis.host", "localhost");
  private static final int PORT = Integer.getInteger("idempotency.redis.port", 6380);
  private static final Duration TTL = Duration.ofMinutes(5);

  private static LettuceConnectionFactory factory;
  private static RedisIdempotencyStorage storage;
  private static StringRedisTemplate redis;

  @BeforeAll
  static void connect() {
    assumeTrue(reachable(), () -> "no Redis on " + HOST + ":" + PORT + ", skipping");
    factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(HOST, PORT));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    storage = new RedisIdempotencyStorage(redis);
  }

  @AfterAll
  static void disconnect() {
    if (factory != null) {
      factory.destroy();
    }
  }

  private static boolean reachable() {
    try (Socket socket = new Socket(HOST, PORT)) {
      return socket.isConnected();
    } catch (Exception e) {
      return false;
    }
  }

  private static String freshKey() {
    return "idempotency-kit-it:" + UUID.randomUUID();
  }

  @Test
  @DisplayName("an untouched key reads as empty, a stored value reads back")
  void storeAndGet() {
    String key = freshKey();
    assertThat(storage.get(key)).isEmpty();

    storage.store(key, "RESULT:{\"a\":1}", TTL);

    assertThat(storage.get(key)).contains("RESULT:{\"a\":1}");
  }

  @Test
  @DisplayName("the lock is exclusive across callers")
  void lockIsExclusive() {
    String key = freshKey();
    assertThat(storage.acquireLock(key, TTL)).isNotNull();
    assertThat(storage.acquireLock(key, TTL)).isNull();
  }

  @Test
  @DisplayName("a held lock reads as PROCESSING, with the token hidden")
  void heldLockReadsAsProcessing() {
    String key = freshKey();
    String token = storage.acquireLock(key, TTL);

    assertThat(storage.get(key)).contains("PROCESSING");
    assertThat(storage.get(key)).get().asString().doesNotContain(token);
  }

  @Test
  @DisplayName("the Lua release only fires for the matching token")
  void releaseIsTokenAware() {
    String key = freshKey();
    String token = storage.acquireLock(key, TTL);

    // A stale release from a timed-out request must not free a lock that now belongs to a
    // different in-flight operation. This is the whole reason the release is a Lua script.
    storage.releaseLock(key, "some-other-token");
    assertThat(storage.get(key)).contains("PROCESSING");

    storage.releaseLock(key, token);
    assertThat(storage.get(key)).isEmpty();
  }

  @Test
  @DisplayName("storing a result takes over the lock, and a late release can't wipe it")
  void storingAResultTakesOverTheLock() {
    String key = freshKey();
    String token = storage.acquireLock(key, TTL);

    storage.store(key, "RESULT:42", TTL);
    storage.releaseLock(key, token);

    assertThat(storage.get(key)).contains("RESULT:42");
  }

  @Test
  @DisplayName("the lock carries a TTL, so a crashed holder can't block the key forever")
  void lockExpiresOnItsOwn() {
    String key = freshKey();
    storage.acquireLock(key, Duration.ofSeconds(30));

    Long ttl = redis.getExpire(key);

    assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(30);
  }
}
