package io.github.plxarr.idempotency.storage.caffeine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the storage contract the aspect relies on. The lock behaviour is the subtle part:
 * an adapter that releases someone else's lock, or that leaks the internal token to the
 * aspect, breaks idempotency in ways that only show up under concurrency.
 */
class CaffeineIdempotencyStorageTest {

  private static final Duration TTL = Duration.ofMinutes(5);

  private CaffeineIdempotencyStorage storage;

  @BeforeEach
  void setUp() {
    storage = new CaffeineIdempotencyStorage();
  }

  @Test
  @DisplayName("an untouched key reads as empty")
  void missingKeyIsEmpty() {
    assertThat(storage.get("nope")).isEmpty();
  }

  @Test
  @DisplayName("stored values read back")
  void storedValueReadsBack() {
    storage.store("k", "RESULT:{\"a\":1}", TTL);
    assertThat(storage.get("k")).contains("RESULT:{\"a\":1}");
  }

  @Test
  @DisplayName("the lock is exclusive: the second caller gets nothing")
  void lockIsExclusive() {
    assertThat(storage.acquireLock("k", TTL)).isNotNull();
    assertThat(storage.acquireLock("k", TTL)).isNull();
  }

  @Test
  @DisplayName("a held lock reads as PROCESSING, with the token hidden")
  void heldLockReadsAsProcessing() {
    String token = storage.acquireLock("k", TTL);

    // The aspect compares against the bare marker; leaking "PROCESSING:<token>" would make
    // every state check fail to match.
    assertThat(storage.get("k")).contains("PROCESSING");
    assertThat(storage.get("k")).get().asString().doesNotContain(token);
  }

  @Test
  @DisplayName("releasing with the right token frees the key")
  void releaseWithOwnTokenFreesTheKey() {
    String token = storage.acquireLock("k", TTL);

    storage.releaseLock("k", token);

    assertThat(storage.get("k")).isEmpty();
    assertThat(storage.acquireLock("k", TTL)).isNotNull();
  }

  @Test
  @DisplayName("releasing with someone else's token does nothing")
  void releaseWithForeignTokenDoesNothing() {
    String token = storage.acquireLock("k", TTL);

    storage.releaseLock("k", "some-other-token");

    // The dangerous case: a stale release from a timed-out request must not free a lock that
    // now belongs to a different in-flight operation.
    assertThat(storage.get("k")).contains("PROCESSING");
    assertThat(storage.acquireLock("k", TTL)).isNull();
    storage.releaseLock("k", token);
    assertThat(storage.get("k")).isEmpty();
  }

  @Test
  @DisplayName("storing a result overwrites the lock, so there is no lock left to release")
  void storingAResultTakesOverTheLock() {
    String token = storage.acquireLock("k", TTL);

    storage.store("k", "RESULT:42", TTL);

    assertThat(storage.get("k")).contains("RESULT:42");
    // A late release with the original token must not wipe the stored result.
    storage.releaseLock("k", token);
    assertThat(storage.get("k")).contains("RESULT:42");
  }

  @Test
  @DisplayName("keys are independent")
  void keysAreIndependent() {
    assertThat(storage.acquireLock("a", TTL)).isNotNull();
    assertThat(storage.acquireLock("b", TTL)).isNotNull();
  }
}
