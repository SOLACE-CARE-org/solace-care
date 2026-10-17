package care.solace.core.privacy;

/**
 * Payload of {@code core.consent.changed} v1
 * (contracts/events/core/consent.changed.v1.schema.json): Tracking and AI turn processing on or off
 * for the subject (System Design, Event catalog).
 */
record ConsentChanged(String purpose, boolean granted, String version) {

  static final String TYPE = "core.consent.changed";
  static final int VERSION = 1;
  static final String TOPIC = "core.consent";
}
