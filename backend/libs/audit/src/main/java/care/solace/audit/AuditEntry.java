package care.solace.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One audit row. Holds ids and names of things, never values people typed: no names, contact
 * details, notes or answers.
 *
 * @param actor who did it
 * @param action dotted verb, e.g. {@code tracking.entries.read}
 * @param targetType kind of thing touched, e.g. {@code tracking.entry}
 * @param targetId id of the thing touched, if there is one
 * @param subjectId pseudonymous id of the person whose data it is, if any
 * @param outcome whether access was allowed
 * @param reason why, required for break-glass access
 * @param details extra ids or field names
 */
public record AuditEntry(
    Actor actor,
    String action,
    String targetType,
    String targetId,
    String subjectId,
    Outcome outcome,
    String reason,
    Map<String, String> details) {

  public AuditEntry {
    Objects.requireNonNull(actor, "actor");
    requireText(action, "action");
    requireText(targetType, "targetType");
    Objects.requireNonNull(outcome, "outcome");
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  /** Who acted. */
  public record Actor(ActorType type, String id, List<String> roles) {
    public Actor {
      Objects.requireNonNull(type, "type");
      requireText(id, "id");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static Actor system(String component) {
      return new Actor(ActorType.SYSTEM, component, List.of());
    }
  }

  public enum ActorType {
    SEEKER,
    SPECIALIST,
    GUEST,
    STAFF,
    SERVICE,
    SYSTEM
  }

  public enum Outcome {
    ALLOWED,
    DENIED
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
