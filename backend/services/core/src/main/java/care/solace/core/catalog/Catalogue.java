package care.solace.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Reference data other modules check codes against. Labels follow the viewer's language. */
public interface Catalogue {

  /** Enabled session languages in display order, named in {@code viewer}'s language. */
  List<Entry> sessionLanguages(Locale viewer);

  /** Enabled help topics in display order, labelled in {@code viewer}'s language. */
  List<Entry> helpTopics(Locale viewer);

  /** Enabled professions a specialist can apply as, labelled in {@code viewer}'s language. */
  List<Entry> professions(Locale viewer);

  /** Enabled specialties in display order, labelled in {@code viewer}'s language (D31). */
  List<Entry> specialties(Locale viewer);

  /** Enabled specialties in display order, with the main specialty each sits under (D62). */
  List<Specialty> specialtyTree(Locale viewer);

  /** Every profession, enabled or not, for staff screens showing older applications. */
  List<Entry> allProfessions(Locale viewer);

  /** Every specialty, enabled or not, for what a specialist was approved for before (D62). */
  List<Entry> allSpecialties(Locale viewer);

  /** True when every code names an enabled session language. */
  boolean areSessionLanguages(Collection<String> codes);

  /** True when every code names an enabled help topic. */
  boolean areHelpTopics(Collection<String> codes);

  /** True when the code names an enabled profession. */
  boolean isProfession(String code);

  /** True when the code names a specialty, enabled or not. */
  boolean specialtyExists(String code);

  /** True when the code names an enabled specialty with no main specialty above it. */
  boolean isMainSpecialty(String code);

  /** The enabled specialties a profession allows (D62); empty for an unknown one. */
  Set<String> allowedSpecialties(String profession);

  /** The professional for a specialty ("Clinical psychologist" for clinical psychology). */
  Optional<String> professionFor(String specialty, Locale viewer);

  /**
   * Adds an enabled specialty under a main one, in EN and FR, and tells Tracking and AI (D62).
   * Callers check the code is free and the parent is a main specialty, and audit the change.
   */
  void createSpecialty(String code, String labelEn, String labelFr, String parentCode);

  /** Lets a profession offer a specialty; true when the link is new (D62). */
  boolean linkSpecialty(String profession, String specialty);

  /** A code and its label in one language. */
  record Entry(String code, String label) {}

  /** A specialty and the main specialty it sits under, null for a main one. */
  record Specialty(String code, String label, String parentCode) {}
}
