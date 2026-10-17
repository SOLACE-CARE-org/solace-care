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

  /** True when every code names an enabled session language. */
  boolean areSessionLanguages(Collection<String> codes);

  /** True when every code names an enabled help topic. */
  boolean areHelpTopics(Collection<String> codes);

  /** A code and its label in one language. */
  record Entry(String code, String label) {}
}
