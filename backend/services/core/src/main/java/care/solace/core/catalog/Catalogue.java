package care.solace.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

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

  /** Every profession, enabled or not, for staff screens showing older applications. */
  List<Entry> allProfessions(Locale viewer);

  /** True when every code names an enabled session language. */
  boolean areSessionLanguages(Collection<String> codes);

  /** True when every code names an enabled help topic. */
  boolean areHelpTopics(Collection<String> codes);

  /** True when the code names an enabled profession. */
  boolean isProfession(String code);

  /** A code and its label in one language. */
  record Entry(String code, String label) {}
}
