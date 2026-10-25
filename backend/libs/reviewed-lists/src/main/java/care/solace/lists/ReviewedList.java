package care.solace.lists;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;

/**
 * A list that safety or clinical meaning depends on, kept as versions in the service's {@code
 * reviewed_list_version} table (D61): guardrail words, safety phrases, evaluation sets, the
 * companion's corpus. A drafter writes a version; a different approver signs it off, and only after
 * {@link #check} passes. The list in code is version 0, in force until a version is live.
 */
public interface ReviewedList {

  /** Stable code: {@code ai.guardrails.clinical}. */
  String code();

  /** What the list holds, for staff. */
  String description();

  /** The list as the code has it: in force until a version is signed off. */
  JsonNode defaults();

  /** Refuses entries the list cannot use, with a reason staff can act on. */
  void validate(JsonNode entries);

  /** What must pass before a draft can go live, such as the safety set; empty when it passed. */
  default Optional<String> check(JsonNode entries) {
    return Optional.empty();
  }

  /** A short record of the check, kept with the version (e.g. "recall EN 100, FR 99.2"). */
  default String checkSummary(JsonNode entries) {
    return null;
  }

  /** Puts the entries in force in this instance; {@code version} 0 is the code's list. */
  void apply(JsonNode live, int version);

  /** Roles that may draft a version. */
  default Set<String> drafters() {
    return Set.of("CLINICAL_REVIEWER", "SUPER_ADMIN");
  }

  /** Roles that may sign one off; never the person who drafted it. */
  default Set<String> approvers() {
    return Set.of("CLINICAL_REVIEWER");
  }
}
