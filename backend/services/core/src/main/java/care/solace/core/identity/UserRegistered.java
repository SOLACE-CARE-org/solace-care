package care.solace.core.identity;

/**
 * Payload of {@code core.user.registered} v1
 * (contracts/events/core/user.registered.v1.schema.json).
 *
 * @param accountType SEEKER or SPECIALIST
 */
record UserRegistered(String accountType) {

  static final String TYPE = "core.user.registered";
  static final int VERSION = 1;
  static final String TOPIC = "core.user";
}
