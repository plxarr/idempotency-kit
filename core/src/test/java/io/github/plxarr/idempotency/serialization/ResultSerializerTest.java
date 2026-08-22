package io.github.plxarr.idempotency.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.plxarr.idempotency.exception.IdempotencySerializationException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultSerializerTest {

  private final ResultSerializer serializer = new ResultSerializer(new ObjectMapper());

  record Person(String name, int age) {}

  @SuppressWarnings("unused")
  static class Signatures {
    Person person() { return null; }
    List<Person> people() { return null; }
    Map<String, List<Integer>> nested() { return null; }
    int primitive() { return 0; }
    void nothing() {}
  }

  private static java.lang.reflect.Type returnTypeOf(String method) throws NoSuchMethodException {
    Method m = Signatures.class.getDeclaredMethod(method);
    return m.getGenericReturnType();
  }

  @Test
  @DisplayName("round-trips a plain object")
  void roundTripsAnObject() throws Exception {
    Person original = new Person("Ada", 36);
    Object back = serializer.deserialize(serializer.serialize(original), returnTypeOf("person"));
    assertThat(back).isEqualTo(original);
  }

  @Test
  @DisplayName("keeps the element type of a generic return, not a list of maps")
  void keepsGenericElementType() throws Exception {
    List<Person> original = List.of(new Person("Ada", 36), new Person("Alan", 41));

    Object back = serializer.deserialize(serializer.serialize(original), returnTypeOf("people"));

    // The point of building the JavaType from the generic return type: without it Jackson
    // would hand back List<LinkedHashMap> and the caller would fail on the cast.
    assertThat(back).isEqualTo(original);
    assertThat((List<?>) back).first().isInstanceOf(Person.class);
  }

  @Test
  @DisplayName("handles a nested generic type")
  void handlesNestedGenerics() throws Exception {
    Map<String, List<Integer>> original = Map.of("a", List.of(1, 2), "b", List.of(3));
    assertThat(serializer.deserialize(serializer.serialize(original), returnTypeOf("nested")))
        .isEqualTo(original);
  }

  @Test
  @DisplayName("handles a primitive return type")
  void handlesPrimitives() throws Exception {
    assertThat(serializer.deserialize(serializer.serialize(42), returnTypeOf("primitive")))
        .isEqualTo(42);
  }

  @Test
  @DisplayName("a void method deserializes to null instead of failing")
  void voidBecomesNull() throws Exception {
    assertThat(serializer.deserialize(serializer.serialize(null), returnTypeOf("nothing")))
        .isNull();
  }

  @Test
  @DisplayName("a null result round-trips as null")
  void nullRoundTrips() throws Exception {
    assertThat(serializer.serialize(null)).isEqualTo("null");
    assertThat(serializer.deserialize("null", returnTypeOf("person"))).isNull();
  }

  @Test
  @DisplayName("malformed cached JSON fails with the library's own exception")
  void malformedJsonIsWrapped() {
    assertThatThrownBy(() -> serializer.deserialize("{not json", Person.class))
        .isInstanceOf(IdempotencySerializationException.class)
        .hasMessageContaining("Failed to deserialize");
  }
}
