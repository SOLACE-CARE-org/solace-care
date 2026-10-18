package care.solace.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Slot holds in Redis (System Design: Redis for slot holds). Each hold is a key that expires on its
 * own; a per-specialist sorted set lists them for the slot view and is pruned as holds expire.
 * Holds are a courtesy during checkout: the calendar's exclusion constraint is what makes double
 * booking impossible.
 */
@Repository
class HoldStore {

  private static final String HOLD = "solace:hold:";
  private static final String BY_SPECIALIST = "solace:holds:";

  private final StringRedisTemplate redis;

  HoldStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  void put(Availability.Hold hold) {
    String value =
        String.join(
            "|",
            hold.specialistId().toString(),
            Long.toString(hold.start().toEpochMilli()),
            Long.toString(hold.end().toEpochMilli()),
            hold.modality(),
            Long.toString(hold.expiresAt().toEpochMilli()));
    redis.opsForValue().set(HOLD + hold.id(), value, Duration.ofMinutes(Availability.HOLD_MINUTES));
    String set = BY_SPECIALIST + hold.specialistId();
    redis.opsForZSet().add(set, hold.id().toString(), hold.expiresAt().toEpochMilli());
    redis.expire(set, Duration.ofMinutes(Availability.HOLD_MINUTES + 1));
  }

  Optional<Availability.Hold> find(UUID id) {
    String value = redis.opsForValue().get(HOLD + id);
    if (value == null) {
      return Optional.empty();
    }
    String[] parts = value.split("\\|");
    return Optional.of(
        new Availability.Hold(
            id,
            UUID.fromString(parts[0]),
            Instant.ofEpochMilli(Long.parseLong(parts[1])),
            Instant.ofEpochMilli(Long.parseLong(parts[2])),
            parts[3],
            Instant.ofEpochMilli(Long.parseLong(parts[4]))));
  }

  /** Live holds on a specialist's calendar. */
  List<Availability.Hold> forSpecialist(UUID specialistId, Instant now) {
    String set = BY_SPECIALIST + specialistId;
    redis.opsForZSet().removeRangeByScore(set, 0, now.toEpochMilli());
    Set<String> ids = redis.opsForZSet().range(set, 0, -1);
    List<Availability.Hold> holds = new ArrayList<>();
    if (ids == null) {
      return holds;
    }
    for (String id : ids) {
      find(UUID.fromString(id))
          .ifPresentOrElse(holds::add, () -> redis.opsForZSet().remove(set, id));
    }
    return holds;
  }

  void delete(UUID id) {
    find(id)
        .ifPresent(
            hold -> redis.opsForZSet().remove(BY_SPECIALIST + hold.specialistId(), id.toString()));
    redis.delete(HOLD + id);
  }
}
