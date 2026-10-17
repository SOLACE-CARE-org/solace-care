package care.solace.core.system;

/**
 * Payload of {@code core.system.pinged} v1 (contracts/events/core/system.pinged.v1.schema.json).
 *
 * @param trackingReachable Tracking answered within the deadline
 * @param aiReachable AI answered within the deadline
 */
public record SystemPinged(boolean trackingReachable, boolean aiReachable) {

  public static final String TYPE = "core.system.pinged";
  public static final int VERSION = 1;
}
