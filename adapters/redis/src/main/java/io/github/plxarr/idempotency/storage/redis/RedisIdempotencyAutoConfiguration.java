package io.github.plxarr.idempotency.storage.redis;

import io.github.plxarr.idempotency.storage.IdempotencyStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Automatically creates a {@link RedisIdempotencyStorage} if there's a
 * {@link StringRedisTemplate} in the context and the application didn't define another
 * {@link IdempotencyStorage}.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisIdempotencyAutoConfiguration {

  /**
   * @param redisTemplate the application's template
   * @return a Redis-backed storage, unless the application already defined one
   */
  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  @ConditionalOnMissingBean(IdempotencyStorage.class)
  public IdempotencyStorage redisIdempotencyStorage(StringRedisTemplate redisTemplate) {
    return new RedisIdempotencyStorage(redisTemplate);
  }
}
