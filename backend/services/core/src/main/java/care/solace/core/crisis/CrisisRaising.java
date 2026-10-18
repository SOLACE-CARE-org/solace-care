package care.solace.core.crisis;

import java.util.UUID;

/**
 * Raises a crisis alert from a source other than the safety monitor: the health check's locked
 * safety rule (System Design, Health check; D55). One alert per source item; raising twice is a
 * no-op. Carries ids and a category only, never what the person answered.
 */
public interface CrisisRaising {

  void raise(
      String source,
      UUID sourceId,
      UUID subjectId,
      boolean guest,
      String category,
      String language);
}
