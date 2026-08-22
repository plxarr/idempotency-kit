package io.github.plxarr.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.plxarr.idempotency.aspect.IdempotentAspect;
import io.github.plxarr.idempotency.serialization.ResultSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class IdempotencyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

  @Test
  @DisplayName("starts in an application with no ObjectMapper bean")
  void startsWithoutAnObjectMapper() {
    // Regression: Spring Boot only auto-configures an ObjectMapper when spring-web is on the
    // classpath, so requiring the bean outright broke every non-web application — with an
    // error that pointed at Jackson instead of at the cause.
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ResultSerializer.class);
          assertThat(context).hasSingleBean(IdempotentAspect.class);
          // And the fallback stays private: the library must not publish an ObjectMapper
          // bean that other parts of the application might pick up.
          assertThat(context).doesNotHaveBean(ObjectMapper.class);
        });
  }

  @Test
  @DisplayName("uses the application's ObjectMapper when there is one")
  void usesTheApplicationObjectMapper() {
    runner
        .withUserConfiguration(IndentingMapperConfig.class)
        .run(
            context -> {
              String json = context.getBean(ResultSerializer.class).serialize(new Point(1, 2));
              // Indentation only appears if the app's configured mapper was the one used.
              assertThat(json).contains("\n");
            });
  }

  @Test
  @DisplayName("an application-defined ResultSerializer wins")
  void backsOffToAUserDefinedSerializer() {
    runner
        .withUserConfiguration(CustomSerializerConfig.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ResultSerializer.class);
              assertThat(context.getBean(ResultSerializer.class).serialize(new Point(1, 2)))
                  .isEqualTo("fixed");
            });
  }

  record Point(int x, int y) {}

  @Configuration
  static class IndentingMapperConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
  }

  @Configuration
  static class CustomSerializerConfig {
    @Bean
    ResultSerializer idempotencyResultSerializer() {
      return new ResultSerializer(new ObjectMapper()) {
        @Override
        public String serialize(Object value) {
          return "fixed";
        }
      };
    }
  }
}
