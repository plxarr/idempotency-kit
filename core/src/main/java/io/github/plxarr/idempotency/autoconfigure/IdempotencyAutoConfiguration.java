package io.github.plxarr.idempotency.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.plxarr.idempotency.aspect.IdempotentAspect;
import io.github.plxarr.idempotency.serialization.ResultSerializer;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the core's cross-cutting pieces: the {@link ResultSerializer} and the
 * {@link IdempotentAspect}. The {@code IdempotencyManager} and the
 * {@code IdempotencyStorage} are provided by the application (or by an adapter's
 * autoconfiguration).
 */
@AutoConfiguration
public class IdempotencyAutoConfiguration {

  /**
   * Serializes cached results with the application's own {@code ObjectMapper} when there is
   * one, so results honour whatever it was configured with.
   *
   * <p>Spring Boot only auto-configures an {@code ObjectMapper} when {@code spring-web} is on
   * the classpath, so a non-web application has none — and requiring the bean outright made
   * this library fail to start there, with an error that pointed at Jackson rather than at
   * the real cause. Falling back to a default mapper keeps the library usable outside a web
   * application. The fallback also covers an ambiguous context (several mappers, none
   * primary), where picking one arbitrarily would be worse than using a plain one.
   *
   * <p>Define your own {@link ResultSerializer} bean to take over entirely.
   */
  @Bean
  @ConditionalOnMissingBean
  public ResultSerializer idempotencyResultSerializer(ObjectProvider<ObjectMapper> objectMapper) {
    return new ResultSerializer(objectMapper.getIfUnique(ObjectMapper::new));
  }

  /**
   * @param beanFactory injected so the aspect can resolve managers and strategies by name
   * @param serializer the bean above, or the application's own
   * @return the aspect, unless the application defined its own
   */
  @Bean
  @ConditionalOnMissingBean
  public IdempotentAspect idempotentAspect(
      ListableBeanFactory beanFactory, ResultSerializer serializer) {
    return new IdempotentAspect(beanFactory, serializer);
  }
}
