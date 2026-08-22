package io.github.plxarr.idempotency.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.plxarr.idempotency.annotation.Idempotent;
import io.github.plxarr.idempotency.concurrent.ConcurrentStrategy;
import io.github.plxarr.idempotency.exception.ExceptionHandlingStrategy;
import io.github.plxarr.idempotency.exception.IdempotencyConfigurationException;
import io.github.plxarr.idempotency.exception.IdempotencyConflictException;
import io.github.plxarr.idempotency.exception.IdempotencyKeyException;
import io.github.plxarr.idempotency.manager.IdempotencyManager;
import io.github.plxarr.idempotency.serialization.ResultSerializer;
import io.github.plxarr.idempotency.testsupport.InMemoryIdempotencyStorage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

class IdempotentAspectTest {

  private AnnotationConfigApplicationContext context;
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigApplicationContext(TestConfig.class);
    pool = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    context.close();
  }

  private Orders orders() {
    return context.getBean(Orders.class);
  }

  /**
   * The counters live outside {@link Orders} on purpose. Spring builds a CGLIB proxy for a
   * class with advised methods and instantiates it <b>without running the target's
   * constructor</b>, so a field read through the proxy reference sees null, not the target's
   * value. Same family of trap as self-invocation: the proxy is not the object you wrote.
   */
  private Probe probe() {
    return context.getBean(Probe.class);
  }

  // ---------------------------------------------------------------------------
  // Caching the happy path
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a repeat with the same key returns the cached result without re-running")
  void repeatReturnsCachedResult() {
    Orders orders = orders();

    assertThat(orders.create("a")).isEqualTo("order-1-a");
    assertThat(orders.create("a")).isEqualTo("order-1-a");
    assertThat(probe().runs).hasValue(1);
  }

  @Test
  @DisplayName("different keys are independent")
  void differentKeysAreIndependent() {
    Orders orders = orders();

    assertThat(orders.create("a")).isEqualTo("order-1-a");
    assertThat(orders.create("b")).isEqualTo("order-2-b");
    assertThat(probe().runs).hasValue(2);
  }

  @Test
  @DisplayName("a null result is cached as a result, not treated as a miss")
  void nullResultIsCached() {
    Orders orders = orders();

    assertThat(orders.nullable("a")).isNull();
    assertThat(orders.nullable("a")).isNull();
    // The danger this pins down: caching null and then treating the cache hit as "nothing
    // stored" would silently re-run the operation.
    assertThat(probe().runs).hasValue(1);
  }

  @Test
  @DisplayName("a generic return type survives the cache round-trip")
  void genericReturnTypeSurvives() {
    Orders orders = orders();

    assertThat(orders.lines("a")).containsExactly("line-a-1", "line-a-2");
    List<String> cached = orders.lines("a");
    assertThat(cached).containsExactly("line-a-1", "line-a-2");
    assertThat(cached).first().isInstanceOf(String.class);
    assertThat(probe().runs).hasValue(1);
  }

  // ---------------------------------------------------------------------------
  // Keys and configuration
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a null or blank key is rejected before anything runs")
  void blankKeyIsRejected() {
    Orders orders = orders();

    assertThatThrownBy(() -> orders.create(null)).isInstanceOf(IdempotencyKeyException.class);
    assertThatThrownBy(() -> orders.create("  ")).isInstanceOf(IdempotencyKeyException.class);
    assertThat(probe().runs).hasValue(0);
  }

  @Test
  @DisplayName("with several managers and none primary, omitting `manager` is a configuration error")
  void ambiguousManagerIsRejected() {
    Orders orders = orders();

    assertThatThrownBy(() -> orders.noManager("a"))
        .isInstanceOf(IdempotencyConfigurationException.class)
        .hasMessageContaining("Multiple IdempotencyManager beans");
    assertThat(probe().runs).hasValue(0);
  }

  @Test
  @DisplayName("self-invocation bypasses the aspect: the proxy is never crossed")
  void selfInvocationBypasses() {
    Orders orders = orders();

    orders.createTwiceInternally("a");

    // Two real executions, because an internal call never reaches the proxy. Same rule as
    // @Transactional.
    assertThat(probe().runs).hasValue(2);
  }

  // ---------------------------------------------------------------------------
  // Concurrency
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("REJECT turns a duplicate away while the first call is still running")
  void rejectRefusesAnInFlightDuplicate() throws Exception {
    Orders orders = orders();
    Future<String> first = pool.submit(() -> orders.slowReject("a"));
    assertThat(probe().started.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> orders.slowReject("a"))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("already in progress");

    probe().release.countDown();
    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("slow-1-a");
    assertThat(probe().runs).hasValue(1);
  }

  @Test
  @DisplayName("WAIT blocks the duplicate and hands it the first call's result")
  void waitReceivesTheFirstResult() throws Exception {
    Orders orders = orders();
    Future<String> first = pool.submit(() -> orders.slowWait("a"));
    assertThat(probe().started.await(5, TimeUnit.SECONDS)).isTrue();

    Future<String> second = pool.submit(() -> orders.slowWait("a"));
    Thread.sleep(100); // let the duplicate get into its polling loop
    probe().release.countDown();

    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("slow-1-a");
    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("slow-1-a");
    assertThat(probe().runs).hasValue(1);
  }

  @Test
  @DisplayName("WAIT gives up once the wait budget runs out")
  void waitTimesOut() {
    Orders orders = orders();
    // An operation that is in progress and never finishes — the node running it died.
    context.getBean(InMemoryIdempotencyStorage.class).markProcessing("stuck");

    assertThatThrownBy(() -> orders.shortWait("stuck"))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("Timed out");
    assertThat(probe().runs).hasValue(0);
  }

  @Test
  @DisplayName("WAIT reports a conflict when the in-progress entry vanishes")
  void waitDetectsAVanishedLock() {
    Orders orders = orders();
    InMemoryIdempotencyStorage storage = context.getBean(InMemoryIdempotencyStorage.class);
    storage.markProcessing("ghost");

    // The lock expires mid-wait without leaving a result behind: the executor crashed.
    pool.submit(
        () -> {
          Thread.sleep(80);
          storage.map.remove("ghost");
          return null;
        });

    assertThatThrownBy(() -> orders.shortWait("ghost"))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("vanished");
  }

  // ---------------------------------------------------------------------------
  // Errors
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an error the strategy caches is replayed without re-running the method")
  void cachedErrorIsReplayed() {
    Orders orders = orders();

    assertThatThrownBy(() -> orders.failing("a"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("DECLINED");
    assertThatThrownBy(() -> orders.failing("a"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("DECLINED");

    assertThat(probe().runs).hasValue(1);
  }

  @Test
  @DisplayName("an error the strategy declines to cache leaves the operation retryable")
  void uncachedErrorStaysRetryable() {
    Orders orders = orders();

    // The default strategy caches nothing, so the lock is released and the next call is a
    // real retry rather than a replay of the failure.
    assertThatThrownBy(() -> orders.failingUncached("a")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> orders.failingUncached("a")).isInstanceOf(BusinessException.class);

    assertThat(probe().runs).hasValue(2);
  }

  @Test
  @DisplayName("after an uncached failure the same key can still succeed")
  void retryAfterUncachedFailureSucceeds() {
    Orders orders = orders();

    assertThatThrownBy(() -> orders.flaky("a")).isInstanceOf(BusinessException.class);
    assertThatNoException().isThrownBy(() -> orders.flaky("a"));
    assertThat(orders.flaky("a")).isEqualTo("ok-a");
    assertThat(probe().runs).hasValue(2); // failed once, succeeded once, then served from cache
  }

  // ---------------------------------------------------------------------------

  static class BusinessException extends RuntimeException {
    BusinessException(String code) {
      super(code);
    }
  }

  /** Caches business errors only, reconstructing them from their code. */
  static class BusinessExceptionStrategy implements ExceptionHandlingStrategy {
    @Override
    public boolean shouldCache(Throwable ex) {
      return ex instanceof BusinessException;
    }

    @Override
    public String serialize(Throwable ex) {
      return ex.getMessage();
    }

    @Override
    public Throwable deserialize(String cached) {
      return new BusinessException(cached);
    }
  }

  @Configuration
  @EnableAspectJAutoProxy
  static class TestConfig {

    @Bean
    InMemoryIdempotencyStorage storage() {
      return new InMemoryIdempotencyStorage();
    }

    @Bean
    ResultSerializer resultSerializer() {
      return new ResultSerializer(new ObjectMapper());
    }

    @Bean
    IdempotentAspect idempotentAspect(ListableBeanFactory beanFactory, ResultSerializer serializer) {
      return new IdempotentAspect(beanFactory, serializer);
    }

    @Bean
    BusinessExceptionStrategy businessExceptionStrategy() {
      return new BusinessExceptionStrategy();
    }

    @Bean("rejectManager")
    IdempotencyManager rejectManager(InMemoryIdempotencyStorage storage) {
      return IdempotencyManager.builder()
          .storage(storage)
          .defaultOnConcurrent(ConcurrentStrategy.REJECT)
          .build();
    }

    @Bean("waitManager")
    IdempotencyManager waitManager(InMemoryIdempotencyStorage storage) {
      return IdempotencyManager.builder()
          .storage(storage)
          .defaultOnConcurrent(ConcurrentStrategy.WAIT)
          .defaultConcurrentWaitTimeoutMs(5_000)
          .build();
    }

    @Bean("shortWaitManager")
    IdempotencyManager shortWaitManager(InMemoryIdempotencyStorage storage) {
      return IdempotencyManager.builder()
          .storage(storage)
          .defaultOnConcurrent(ConcurrentStrategy.WAIT)
          .defaultConcurrentWaitTimeoutMs(300)
          .build();
    }

    @Bean
    Probe probe() {
      return new Probe();
    }

    @Bean
    Orders orders(Probe probe) {
      return new Orders(probe);
    }
  }

  /** Mutable test state, kept in an unproxied bean so the tests can read it reliably. */
  static class Probe {
    final AtomicInteger runs = new AtomicInteger();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
  }

  /** Every method names its manager: with four in the context and none primary, it has to. */
  static class Orders {

    private final Probe probe;

    Orders(Probe probe) {
      this.probe = probe;
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public String create(String id) {
      return "order-" + probe.runs.incrementAndGet() + "-" + id;
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public String nullable(String id) {
      probe.runs.incrementAndGet();
      return null;
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public List<String> lines(String id) {
      probe.runs.incrementAndGet();
      return List.of("line-" + id + "-1", "line-" + id + "-2");
    }

    /** No `manager`, which is ambiguous here on purpose. */
    @Idempotent(key = "#id")
    public String noManager(String id) {
      return "never-" + probe.runs.incrementAndGet();
    }

    public void createTwiceInternally(String id) {
      create(id);
      create(id);
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public String slowReject(String id) throws InterruptedException {
      return slow(id);
    }

    @Idempotent(key = "#id", manager = "waitManager")
    public String slowWait(String id) throws InterruptedException {
      return slow(id);
    }

    private String slow(String id) throws InterruptedException {
      String result = "slow-" + probe.runs.incrementAndGet() + "-" + id;
      probe.started.countDown();
      probe.release.await(5, TimeUnit.SECONDS);
      return result;
    }

    @Idempotent(key = "#id", manager = "shortWaitManager")
    public String shortWait(String id) {
      return "short-" + probe.runs.incrementAndGet();
    }

    @Idempotent(key = "#id", manager = "rejectManager", onException = BusinessExceptionStrategy.class)
    public String failing(String id) {
      probe.runs.incrementAndGet();
      throw new BusinessException("DECLINED");
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public String failingUncached(String id) {
      probe.runs.incrementAndGet();
      throw new BusinessException("DECLINED");
    }

    @Idempotent(key = "#id", manager = "rejectManager")
    public String flaky(String id) {
      if (probe.runs.incrementAndGet() == 1) {
        throw new BusinessException("TRANSIENT");
      }
      return "ok-" + id;
    }
  }
}
