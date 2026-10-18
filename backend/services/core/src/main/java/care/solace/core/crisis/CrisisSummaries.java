package care.solace.core.crisis;

import java.util.UUID;

/** How many crisis alerts are open for a person, for the Person record (D45). */
public interface CrisisSummaries {

  int openFor(UUID subjectId);
}
