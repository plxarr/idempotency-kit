package io.github.plxarr.idempotency.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.plxarr.idempotency.concurrent.ConcurrentStrategy;
import io.github.plxarr.idempotency.exception.ExceptionHandlingStrategy;
import io.github.plxarr.idempotency.testsupport.InMemoryIdempotencyStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyManagerTest {

  @Test
  @DisplayName("only storage is required; everything else has a built-in default")
  void onlyStorageIsRequired() {
    IdempotencyManager manager =
        IdempotencyManager.builder().storage(new InMemoryIdempotencyStorage()).build();

    assertThat(manager.defaultTtlMs()).isEqualTo(300_000);
    assertThat(manager.defaultOnConcurrent()).isEqualTo(ConcurrentStrategy.REJECT);
    assertThat(manager.defaultConcurrentWaitTimeoutMs()).isEqualTo(10_000);
    assertThat(manager.defaultExceptionStrategy())
        .isInstanceOf(ExceptionHandlingStrategy.Default.class);
  }

  @Test
  @DisplayName("a manager without storage is refused")
  void storageIsMandatory() {
    assertThatThrownBy(() -> IdempotencyManager.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("storage is required");
  }

  @Test
  @DisplayName("the WAIT backoff ceiling scales with the wait budget but never goes below the floor")
  void backoffCeilingScalesWithTheBudget() {
    // A tenth of the budget, so a long wait polls lazily instead of hammering the backend...
    assertThat(IdempotencyManager.maxIntervalFor(10_000)).isEqualTo(1_000);
    // ...but a short budget never produces an interval below the initial one, which would
    // mean busy-waiting.
    assertThat(IdempotencyManager.maxIntervalFor(50))
        .isEqualTo(IdempotencyManager.INITIAL_INTERVAL_MS);
    assertThat(IdempotencyManager.maxIntervalFor(0))
        .isEqualTo(IdempotencyManager.INITIAL_INTERVAL_MS);
  }

  @Test
  @DisplayName("the default exception strategy caches nothing")
  void defaultStrategyCachesNothing() {
    ExceptionHandlingStrategy strategy = new ExceptionHandlingStrategy.Default();

    assertThat(strategy.shouldCache(new RuntimeException("boom"))).isFalse();
    // It is a sentinel, not a working strategy: using it to serialize is a programming error.
    assertThatThrownBy(() -> strategy.serialize(new RuntimeException("boom")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> strategy.deserialize("anything"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
