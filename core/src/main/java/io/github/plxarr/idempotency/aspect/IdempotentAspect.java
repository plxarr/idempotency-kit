package io.github.plxarr.idempotency.aspect;

import io.github.plxarr.idempotency.annotation.Idempotent;
import io.github.plxarr.idempotency.concurrent.ConcurrentStrategy;
import io.github.plxarr.idempotency.exception.ExceptionHandlingStrategy;
import io.github.plxarr.idempotency.exception.IdempotencyConfigurationException;
import io.github.plxarr.idempotency.exception.IdempotencyConflictException;
import io.github.plxarr.idempotency.exception.IdempotencyKeyException;
import io.github.plxarr.idempotency.manager.IdempotencyManager;
import io.github.plxarr.idempotency.serialization.ResultSerializer;
import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Intercepts methods annotated with {@link Idempotent} and applies the idempotency logic:
 * SpEL key resolution, distributed lock, result/error caching, and concurrency policy
 * (REJECT / WAIT with backoff).
 */
@Aspect
public class IdempotentAspect {

  private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

  private static final String RESULT_PREFIX = "RESULT:";
  private static final String ERROR_PREFIX = "ERROR:";
  private static final String PROCESSING = "PROCESSING";

  private final ListableBeanFactory beanFactory;
  private final ResultSerializer serializer;
  private final ExpressionParser parser = new SpelExpressionParser();
  private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

  /**
   * @param beanFactory used to resolve the {@code IdempotencyManager} and the
   *     {@code ExceptionHandlingStrategy} an annotation names, which is why the aspect takes
   *     a factory rather than the objects themselves
   * @param serializer turns results into cache entries and back
   */
  public IdempotentAspect(ListableBeanFactory beanFactory, ResultSerializer serializer) {
    this.beanFactory = beanFactory;
    this.serializer = serializer;
  }

  /**
   * Runs the operation at most once per key, and serves every repeat from the cache.
   *
   * <p>In order: resolve the manager and the effective settings → evaluate the SpEL key →
   * read the entry. A cached {@code RESULT:} is deserialized and returned; a cached
   * {@code ERROR:} is rethrown through the exception strategy; a {@code PROCESSING} entry
   * means a duplicate, handled per {@code onConcurrent}. With no entry at all, the lock is
   * taken and the method runs.
   *
   * <p>The lock and the cached value are the same entry, so writing the result hands the lock
   * off atomically. If nothing gets written — the failure wasn't cached — the lock is released
   * in a {@code finally} so the key stays retryable.
   *
   * @param pjp the intercepted call
   * @param idempotent the annotation on the intercepted method
   * @return the method's result, or the one cached from the first call
   * @throws IdempotencyKeyException if the key resolves to null or blank
   * @throws IdempotencyConflictException on a duplicate under {@code REJECT}, or when a
   *     {@code WAIT} exhausts its budget or finds the in-flight operation gone
   * @throws Throwable whatever the method throws, or whatever the strategy rebuilt from cache
   */
  @Around("@annotation(idempotent)")
  public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
    IdempotencyManager manager = resolveManager(idempotent.manager());
    IdempotencyStorage storage = manager.storage();

    String key = resolveKey(pjp, idempotent.key());
    if (key == null || key.isBlank()) {
      throw new IdempotencyKeyException(
          "Idempotency key resolved to null/blank for expression: " + idempotent.key());
    }

    // Effective parameters: annotation override or manager default.
    ConcurrentStrategy onConcurrent =
        idempotent.onConcurrent() != ConcurrentStrategy.UNSET
            ? idempotent.onConcurrent()
            : manager.defaultOnConcurrent();
    long concurrentWaitTimeoutMs =
        idempotent.concurrentWaitTimeoutMs() >= 0
            ? idempotent.concurrentWaitTimeoutMs()
            : manager.defaultConcurrentWaitTimeoutMs();
    long ttlMs = idempotent.ttlMs() >= 0 ? idempotent.ttlMs() : manager.defaultTtlMs();
    Duration ttl = Duration.ofMillis(ttlMs);
    ExceptionHandlingStrategy exceptionStrategy = resolveExceptionStrategy(idempotent, manager);

    Method method = ((MethodSignature) pjp.getSignature()).getMethod();

    // 1) Is there already something cached?
    Optional<String> cached = storage.get(key);
    if (cached.isPresent()) {
      return handleCached(cached.get(), method, key, onConcurrent, concurrentWaitTimeoutMs, storage, exceptionStrategy);
    }

    // 2) Try to acquire the lock. The lock uses the same unified TTL as the result.
    String token = storage.acquireLock(key, ttl);
    if (token == null) {
      // Someone else took the lock between the get and the acquire: treat as "in progress".
      return onProcessing(key, method, onConcurrent, concurrentWaitTimeoutMs, storage, exceptionStrategy);
    }

    // 3) We have the lock: execute and cache.
    return executeAndCache(pjp, method, key, token, ttl, storage, exceptionStrategy);
  }

  // ---------------------------------------------------------------------------
  // Configuration resolution
  // ---------------------------------------------------------------------------

  private IdempotencyManager resolveManager(String name) {
    if (!name.isBlank()) {
      return beanFactory.getBean(name, IdempotencyManager.class);
    }
    Map<String, IdempotencyManager> beans = beanFactory.getBeansOfType(IdempotencyManager.class);
    if (beans.isEmpty()) {
      throw new IdempotencyConfigurationException("No IdempotencyManager bean found.");
    }
    if (beans.size() == 1) {
      return beans.values().iterator().next();
    }
    // Several: try @Primary; getBean(type) throws if there isn't one.
    try {
      return beanFactory.getBean(IdempotencyManager.class);
    } catch (Exception e) {
      throw new IdempotencyConfigurationException(
          "Multiple IdempotencyManager beans found and no 'manager' specified; "
              + "annotate one as @Primary or set manager=... on @Idempotent.");
    }
  }

  private ExceptionHandlingStrategy resolveExceptionStrategy(
      Idempotent idempotent, IdempotencyManager manager) {
    Class<? extends ExceptionHandlingStrategy> type = idempotent.onException();
    if (type == ExceptionHandlingStrategy.Default.class) {
      return manager.defaultExceptionStrategy();
    }
    return beanFactory.getBean(type);
  }

  private String resolveKey(ProceedingJoinPoint pjp, String spel) {
    try {
      MethodSignature sig = (MethodSignature) pjp.getSignature();
      MethodBasedEvaluationContext ctx =
          new MethodBasedEvaluationContext(
              pjp.getTarget(), sig.getMethod(), pjp.getArgs(), nameDiscoverer);
      return parser.parseExpression(spel).getValue(ctx, String.class);
    } catch (Exception e) {
      throw new IdempotencyKeyException("Error evaluating SpEL key [" + spel + "]: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Handling of cached / in-progress values
  // ---------------------------------------------------------------------------

  private Object handleCached(
      String value,
      Method method,
      String key,
      ConcurrentStrategy onConcurrent,
      long concurrentWaitTimeoutMs,
      IdempotencyStorage storage,
      ExceptionHandlingStrategy exceptionStrategy)
      throws Throwable {

    if (value.startsWith(RESULT_PREFIX)) {
      return serializer.deserialize(value.substring(RESULT_PREFIX.length()), method.getGenericReturnType());
    }
    if (value.startsWith(ERROR_PREFIX)) {
      throw exceptionStrategy.deserialize(value.substring(ERROR_PREFIX.length()));
    }
    // It's PROCESSING: there's an execution in progress.
    return onProcessing(key, method, onConcurrent, concurrentWaitTimeoutMs, storage, exceptionStrategy);
  }

  private Object onProcessing(
      String key,
      Method method,
      ConcurrentStrategy onConcurrent,
      long concurrentWaitTimeoutMs,
      IdempotencyStorage storage,
      ExceptionHandlingStrategy exceptionStrategy)
      throws Throwable {

    if (onConcurrent == ConcurrentStrategy.REJECT) {
      throw new IdempotencyConflictException("Operation already in progress for key: " + key);
    }
    // WAIT: polling with bounded exponential backoff.
    return waitForResult(key, method, concurrentWaitTimeoutMs, storage, exceptionStrategy);
  }

  private Object waitForResult(
      String key,
      Method method,
      long concurrentWaitTimeoutMs,
      IdempotencyStorage storage,
      ExceptionHandlingStrategy exceptionStrategy)
      throws Throwable {

    long elapsed = 0;
    long interval = IdempotencyManager.INITIAL_INTERVAL_MS;
    long maxInterval = IdempotencyManager.maxIntervalFor(concurrentWaitTimeoutMs);

    while (elapsed < concurrentWaitTimeoutMs) {
      long wait = Math.min(interval, concurrentWaitTimeoutMs - elapsed);
      Thread.sleep(wait);
      elapsed += wait;

      Optional<String> current = storage.get(key);
      if (current.isEmpty()) {
        // The lock expired without leaving a result (the executor died): treat as a conflict.
        throw new IdempotencyConflictException(
            "In-progress operation vanished (lock expired) for key: " + key);
      }
      String value = current.get();
      if (value.startsWith(RESULT_PREFIX)) {
        return serializer.deserialize(value.substring(RESULT_PREFIX.length()), method.getGenericReturnType());
      }
      if (value.startsWith(ERROR_PREFIX)) {
        throw exceptionStrategy.deserialize(value.substring(ERROR_PREFIX.length()));
      }
      // still PROCESSING → backoff
      interval = Math.min(interval * 2, maxInterval);
    }
    throw new IdempotencyConflictException(
        "Timed out after " + concurrentWaitTimeoutMs + "ms waiting for in-progress operation, key: " + key);
  }

  // ---------------------------------------------------------------------------
  // Execution with the lock held
  // ---------------------------------------------------------------------------

  private Object executeAndCache(
      ProceedingJoinPoint pjp,
      Method method,
      String key,
      String token,
      Duration ttl,
      IdempotencyStorage storage,
      ExceptionHandlingStrategy exceptionStrategy)
      throws Throwable {

    try {
      Object result = pjp.proceed();
      recordResult(key, result, ttl, storage);
      return result;

    } catch (Throwable ex) {
      boolean cached = false;
      if (exceptionStrategy.shouldCache(ex)) {
        try {
          storage.store(key, ERROR_PREFIX + exceptionStrategy.serialize(ex), ttl);
          cached = true;
        } catch (Exception cacheErr) {
          log.error("Failed to cache error for key {}: {}", key, cacheErr.getMessage());
        }
      }
      if (!cached) {
        try {
          storage.releaseLock(key, token);
        } catch (Exception releaseErr) {
          log.warn("Failed to release lock for key {}: {}", key, releaseErr.getMessage());
        }
      }
      throw ex;
    }
  }

  private void recordResult(
      String key, Object result, Duration ttl, IdempotencyStorage storage) {
    try {
      String value =
          result == null ? RESULT_PREFIX + serializer.serialize(null)
                         : RESULT_PREFIX + serializer.serialize(result);
      storage.store(key, value, ttl);
    } catch (Exception recordErr) {
      log.error(
          "Operation for key {} succeeded but its result could not be recorded, so a duplicate "
              + "arriving after the lock's TTL will run it again: {}",
          key,
          recordErr.getMessage());
    }
  }
}
