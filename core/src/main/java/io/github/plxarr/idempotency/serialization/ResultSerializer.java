package io.github.plxarr.idempotency.serialization;

import io.github.plxarr.idempotency.exception.IdempotencySerializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;

/**
 * Serializes and deserializes method results to/from String, using Jackson.
 * Supports generic types ({@code List<T>}, {@code ResponseEntity<T>}, etc.) by building
 * the {@code JavaType} from the method's generic {@link Type}.
 */
public class ResultSerializer {

  private final ObjectMapper mapper;

  /**
   * @param mapper the mapper to use; the autoconfiguration passes the application's own when
   *     it has one, so cached results honour its configuration
   */
  public ResultSerializer(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * @param value the method's result, possibly {@code null}
   * @return its JSON form, to be stored behind the {@code RESULT:} prefix
   * @throws IdempotencySerializationException if the value can't be written
   */
  public String serialize(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IdempotencySerializationException("Failed to serialize result", e);
    }
  }

  /**
   * Deserializes respecting the method's actual generic return type.
   *
   * @param json the cached value (without the state prefix)
   * @param genericReturnType the result of {@code Method.getGenericReturnType()}
   */
  public Object deserialize(String json, Type genericReturnType) {
    try {
      if (genericReturnType == void.class || genericReturnType == Void.class) {
        return null;
      }
      return mapper.readValue(json, mapper.getTypeFactory().constructType(genericReturnType));
    } catch (Exception e) {
      throw new IdempotencySerializationException(
          "Failed to deserialize cached result into " + genericReturnType, e);
    }
  }
}
