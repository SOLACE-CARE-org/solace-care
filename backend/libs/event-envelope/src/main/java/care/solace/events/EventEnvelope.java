package care.solace.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The wrapper around every Kafka event, matching {@code contracts/events/envelope.schema.json}.
 * Payloads hold ids and facts only: never names, phone numbers, emails, free text or health data.
 *
 * <p>Create envelopes with {@link Events#envelope}, which fills in the id, time and trace id, and
 * publish them with Spring's {@code ApplicationEventPublisher} inside the transaction that made the
 * change. The outbox sends them to Kafka after the commit.
 *
 * @param eventId unique id; consumers are idempotent on it
 * @param type e.g. {@code core.booking.confirmed}
 * @param version major version of the payload schema
 * @param occurredAt when it happened, UTC
 * @param subjectId pseudonymous id of the person it is about; null for platform events
 * @param locale the subject's app language ({@code en}/{@code fr}); null when there is no subject
 * @param traceId W3C trace id, 32 lower-case hex characters
 * @param payload type-specific payload; consumers read it with {@link #payloadAs(Class)}
 */
public record EventEnvelope(
    UUID eventId,
    String type,
    int version,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    String subjectId,
    String locale,
    String traceId,
    Object payload) {

  static final Pattern TYPE = Pattern.compile("^(core|tracking|ai)(\\.[a-z]+)+$");
  static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

  private static final ObjectMapper PAYLOADS =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (type == null || !TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("type must look like core.booking.confirmed: " + type);
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be at least 1");
    }
    if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
      throw new IllegalArgumentException("traceId must be 32 lower-case hex characters");
    }
    if (locale != null && !locale.equals("en") && !locale.equals("fr")) {
      throw new IllegalArgumentException("locale must be en or fr");
    }
  }

  /**
   * Kafka topic: the producer and aggregate, e.g. {@code core.booking} for core.booking.confirmed.
   */
  public String topic() {
    String[] parts = type.split("\\.");
    return parts[0] + "." + parts[1];
  }

  /**
   * Partition key: the subject, so one person's events stay in order (System Design,
   * Infrastructure). Platform events with no subject spread by event id.
   */
  public String partitionKey() {
    return subjectId != null ? subjectId : eventId.toString();
  }

  /** The payload converted to the consumer's own type for this event. */
  public <T> T payloadAs(Class<T> type) {
    return PAYLOADS.convertValue(payload, type);
  }
}
