package care.solace.ai.gateway;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output checks every feature applies before anything reaches a person (System Design, Guardrails):
 * no diagnosis, no condition named, no prescription or dosage, no prediction, and, for
 * explanations, no number that is not in the evidence. Returns the reason a text fails.
 */
public final class Guardrails {

  /** Version 1. Clinicians change it as the reviewed list ai.guardrails.clinical (D61). */
  private static final List<Pattern> CLINICAL =
      List.of(
          Pattern.compile("\\bdiagnos"),
          Pattern.compile("\\byou have (a |an |the )?(condition|disorder|disease|syndrome)"),
          Pattern.compile("\\bvous avez (un|une) (trouble|maladie|syndrome)"),
          Pattern.compile("\\b(disorder|disease|syndrome|illness|maladie|trouble)\\b"),
          Pattern.compile("\\b(depression|bipolar|pcos|sopk|endometriosis|endometriose)\\b"),
          Pattern.compile("\\b(prescri|dosage|dose\\b|mg\\b|milligram|ordonnance|posologie)"),
          Pattern.compile("\\b(predict|likely to|risk of|risque de|prevision|forecast)\\b"),
          Pattern.compile("\\btriage\\b"));

  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

  private static volatile List<Pattern> inForce = CLINICAL;

  private Guardrails() {}

  /** Why the text may not be shown, or empty when it may. */
  public static Optional<String> clinical(String text) {
    String t = fold(text).replace("not a diagnosis", " ").replace("pas un diagnostic", " ");
    for (Pattern p : inForce) {
      if (p.matcher(t).find()) {
        return Optional.of("clinical-wording");
      }
    }
    return Optional.empty();
  }

  /** The patterns of version 1, as written. */
  public static List<String> clinicalDefaults() {
    return CLINICAL.stream().map(Pattern::pattern).toList();
  }

  /** Puts a signed-off version of the clinical words in force (D61). */
  public static void useClinical(List<String> patterns) {
    inForce = patterns.stream().map(Pattern::compile).toList();
  }

  /** Every number in the text must appear in the evidence: the model adds no findings. */
  public static Optional<String> numbersFrom(String text, String evidence) {
    Set<String> allowed = numbers(evidence);
    for (String n : numbers(text)) {
      if (!allowed.contains(n)) {
        return Optional.of("number-not-in-evidence");
      }
    }
    return Optional.empty();
  }

  public static Optional<String> length(String text, int max) {
    return text == null || text.isBlank() || text.length() > max
        ? Optional.of("length")
        : Optional.empty();
  }

  private static Set<String> numbers(String text) {
    Set<String> out = new HashSet<>();
    Matcher m = NUMBER.matcher(text == null ? "" : text);
    while (m.find()) {
      out.add(m.group().replace(',', '.'));
    }
    return out;
  }

  static String fold(String text) {
    return Normalizer.normalize(
            text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
  }
}
