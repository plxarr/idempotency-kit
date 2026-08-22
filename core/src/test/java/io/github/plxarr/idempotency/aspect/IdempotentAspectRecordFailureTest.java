package io.github.plxarr.idempotency.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.plxarr.idempotency.annotation.Idempotent;
import io.github.plxarr.idempotency.exception.IdempotencyConflictException;
import io.github.plxarr.idempotency.manager.IdempotencyManager;
import io.github.plxarr.idempotency.serialization.ResultSerializer;
import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import io.github.plxarr.idempotency.testsupport.InMemoryIdempotencyStorage;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

class IdempotentAspectRecordFailureTest {

  static final AtomicInteger executions = new AtomicInteger();

  private AnnotationConfigApplicationContext context;

  @AfterEach
  void tearDown() {
    executions.set(0);
    if (context != null) {
      context.close();
    }
  }

  @Test
  @DisplayName("a storage that can't write still returns the result to the caller")
  void survivesAStorageThatCannotWrite() {
    context = new AnnotationConfigApplicationContext(UnwritableStorageConfig.class);
    Orders orders = context.getBean(Orders.class);

    assertThat(orders.place("order-1")).isEqualTo("PLACED:order-1");
    assertThat(executions).hasValue(1);
  }

  @Test
  @DisplayName("an unserializable result reaches the caller, and the kept lock blocks the duplicate")
  void survivesAnUnserializableResult() {
    context = new AnnotationConfigApplicationContext(UnserializableResultConfig.class);
    Reports reports = context.getBean(Reports.class);

    assertThatNoException().isThrownBy(() -> reports.build("r-1"));

    assertThatThrownBy(() -> reports.build("r-1"))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(executions).hasValue(1);
  }

  @Test
  @DisplayName("the lock is kept when the result couldn't be recorded")
  void keepsTheLockWhenTheResultCouldNotBeRecorded() {
    context = new AnnotationConfigApplicationContext(UnwritableStorageConfig.class);

    context.getBean(Orders.class).place("order-1");

    assertThat(((StoreFailsStorage) context.getBean(IdempotencyStorage.class)).released).isZero();
  }

  public static class Orders {
    @Idempotent(key = "#id")
    public String place(String id) {
      executions.incrementAndGet();
      return "PLACED:" + id;
    }
  }

  public static class Reports {
    @Idempotent(key = "#id")
    public Unserializable build(String id) {
      executions.incrementAndGet();
      return new Unserializable();
    }
  }

  public static class Unserializable {
    private final String hidden = "x";
  }

  static class StoreFailsStorage implements IdempotencyStorage {
    int released;

    @Override
    public Optional<String> get(String key) {
      return Optional.empty();
    }

    @Override
    public void store(String key, String value, Duration ttl) {
      throw new IllegalStateException("connection reset");
    }

    @Override
    public String acquireLock(String key, Duration ttl) {
      return UUID.randomUUID().toString();
    }

    @Override
    public void releaseLock(String key, String token) {
      released++;
    }
  }

  @Configuration
  @EnableAspectJAutoProxy
  static class UnwritableStorageConfig {
    @Bean
    IdempotencyStorage storage() {
      return new StoreFailsStorage();
    }

    @Bean
    IdempotencyManager manager(IdempotencyStorage storage) {
      return IdempotencyManager.builder().storage(storage).build();
    }

    @Bean
    IdempotentAspect aspect(ListableBeanFactory beanFactory) {
      return new IdempotentAspect(beanFactory, new ResultSerializer(new ObjectMapper()));
    }

    @Bean
    Orders orders() {
      return new Orders();
    }
  }

  @Configuration
  @EnableAspectJAutoProxy
  static class UnserializableResultConfig {
    @Bean
    IdempotencyStorage storage() {
      return new InMemoryIdempotencyStorage();
    }

    @Bean
    IdempotencyManager manager(IdempotencyStorage storage) {
      return IdempotencyManager.builder().storage(storage).build();
    }

    @Bean
    IdempotentAspect aspect(ListableBeanFactory beanFactory) {
      return new IdempotentAspect(beanFactory, new ResultSerializer(new ObjectMapper()));
    }

    @Bean
    Reports reports() {
      return new Reports();
    }
  }
}
