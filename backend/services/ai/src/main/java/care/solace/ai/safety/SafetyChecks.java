package care.solace.ai.safety;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The safety monitor as the gRPC endpoints use it. */
@Component
public class SafetyChecks {

  private final SafetyMonitor monitor;

  SafetyChecks(SafetyMonitor monitor) {
    this.monitor = monitor;
  }

  public record Decision(UUID id, boolean flagged) {}

  public record Stored(
      UUID id,
      String source,
      UUID sourceId,
      UUID subjectId,
      String category,
      String classifierVersion,
      String policyVersion) {}

  public Decision check(String source, UUID sourceId, UUID subjectId, String text, Locale locale) {
    var d = monitor.check(source, sourceId, subjectId, text, locale);
    return new Decision(d.id(), d.flagged());
  }

  public Optional<Stored> decision(UUID id) {
    return monitor
        .decision(id)
        .map(
            s ->
                new Stored(
                    s.id(),
                    s.source(),
                    s.sourceId(),
                    s.subjectId(),
                    s.category(),
                    s.classifierVersion(),
                    s.policyVersion()));
  }
}
