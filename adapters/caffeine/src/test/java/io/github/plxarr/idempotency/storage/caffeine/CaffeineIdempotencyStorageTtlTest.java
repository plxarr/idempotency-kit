package io.github.plxarr.idempotency.storage.caffeine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaffeineIdempotencyStorageTtlTest {

  private final CaffeineIdempotencyStorage storage = new CaffeineIdempotencyStorage();

  @Test
  @DisplayName("each entry expires on the ttl it was written with")
  void entriesExpireOnTheirOwnTtl() throws Exception {
    storage.acquireLock("corta", Duration.ofMillis(150));
    storage.acquireLock("larga", Duration.ofMinutes(10));

    assertThat(storage.get("corta")).contains("PROCESSING");
    assertThat(storage.get("larga")).contains("PROCESSING");

    Thread.sleep(400);
    storage.cache().cleanUp();

    assertThat(storage.get("corta")).isEmpty();
    assertThat(storage.get("larga")).contains("PROCESSING");
  }

  @Test
  @DisplayName("storing a result restarts the ttl from that write")
  void storeRestartsTheTtl() throws Exception {
    storage.acquireLock("k", Duration.ofMillis(200));
    Thread.sleep(150);

    storage.store("k", "RESULT:1", Duration.ofMinutes(10));
    Thread.sleep(200);
    storage.cache().cleanUp();

    assertThat(storage.get("k")).contains("RESULT:1");
  }
}
