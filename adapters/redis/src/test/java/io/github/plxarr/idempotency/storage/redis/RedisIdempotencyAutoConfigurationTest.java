package io.github.plxarr.idempotency.storage.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RedisIdempotencyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RedisIdempotencyAutoConfiguration.class));

  @Test
  @DisplayName("creates the storage when Boot auto-configured the template from properties")
  void createsTheStorageOverABootAutoConfiguredTemplate() {
    runner
        .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(IdempotencyStorage.class);
              assertThat(context.getBean(IdempotencyStorage.class))
                  .isInstanceOf(RedisIdempotencyStorage.class);
            });
  }

  @Test
  @DisplayName("an application-defined storage wins")
  void backsOffToAUserDefinedStorage() {
    runner
        .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
        .withUserConfiguration(CustomStorageConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(IdempotencyStorage.class);
              assertThat(context.getBean(IdempotencyStorage.class))
                  .isNotInstanceOf(RedisIdempotencyStorage.class);
            });
  }

  @Test
  @DisplayName("stays out of the way when there is no template at all")
  void createsNothingWithoutATemplate() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(IdempotencyStorage.class);
        });
  }

  @Configuration
  static class CustomStorageConfig {
    @Bean
    IdempotencyStorage customStorage() {
      return new IdempotencyStorage() {
        @Override
        public Optional<String> get(String key) {
          return Optional.empty();
        }

        @Override
        public void store(String key, String value, Duration ttl) {}

        @Override
        public String acquireLock(String key, Duration ttl) {
          return null;
        }

        @Override
        public void releaseLock(String key, String token) {}
      };
    }
  }
}
