package care.solace.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Value serializer for every Solace producer. Spring Modulith's outbox hands over events it has
 * already turned into JSON bytes, and dead-lettered records arrive as raw bytes; both pass through
 * unchanged. Anything else is written as plain JSON, without Java type headers and with dates as
 * ISO-8601 strings, as the event schemas require.
 */
public class EventJsonSerializer implements Serializer<Object> {

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Override
  public byte[] serialize(String topic, Object data) {
    if (data == null) {
      return null;
    }
    if (data instanceof byte[] bytes) {
      return bytes;
    }
    try {
      return JSON.writeValueAsBytes(data);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Cannot write event for topic " + topic, e);
    }
  }
}
