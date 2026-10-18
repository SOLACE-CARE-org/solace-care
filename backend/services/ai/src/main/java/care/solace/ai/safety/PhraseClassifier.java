package care.solace.ai.safety;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The safety monitor until a model is approved (D39): phrases in English and French, matched on
 * text with accents, case and apostrophes evened out, so code-switched text is caught too. It is
 * deliberately broad: a false alarm costs a responder a minute, a miss can cost a life
 * (threat-model-ai.md: over-flagging is acceptable). Categories are tried in order of severity.
 */
final class PhraseClassifier implements SafetyClassifier {

  static final String VERSION = "phrases-1";

  private static final Map<Category, List<String>> PHRASES = new LinkedHashMap<>();

  static {
    PHRASES.put(
        Category.SUICIDAL_IDEATION,
        List.of(
            // English
            "kill myself",
            "killing myself",
            "end my life",
            "end it all",
            "ending it all",
            "want to die",
            "wanna die",
            "wish i was dead",
            "wish i were dead",
            "suicide",
            "suicidal",
            "better off dead",
            "better off without me",
            "better without me",
            "no reason to live",
            "nothing to live for",
            "don't want to live",
            "dont want to live",
            "do not want to live",
            "can't go on",
            "cant go on",
            "cannot go on",
            "take my own life",
            "not be here anymore",
            "not wake up",
            "how long i can keep going",
            "how long i can hold on",
            // French
            "me tuer",
            "me suicider",
            "suicidaire",
            "mettre fin a mes jours",
            "mettre fin a ma vie",
            "en finir",
            "envie de mourir",
            "veux mourir",
            "voudrais mourir",
            "plus envie de vivre",
            "pas envie de vivre",
            "ne veux plus vivre",
            "veux plus vivre",
            "combien de temps je peux encore tenir",
            "je ne tiens plus",
            "j'en peux plus de vivre",
            "plus la force de vivre",
            "disparaitre pour toujours",
            "mieux sans moi",
            "ne plus me reveiller",
            "plus de raison de vivre"));
    PHRASES.put(
        Category.SELF_HARM,
        List.of(
            "cut myself",
            "cutting myself",
            "hurt myself",
            "hurting myself",
            "harm myself",
            "self harm",
            "self-harm",
            "selfharm",
            "burn myself",
            "burning myself",
            "me couper",
            "me scarifier",
            "scarification",
            "me faire du mal",
            "me blesser",
            "me bruler",
            "m'automutiler",
            "automutilation"));
    PHRASES.put(
        Category.VIOLENT_THOUGHTS,
        List.of(
            "kill him",
            "kill her",
            "kill them",
            "kill someone",
            "hurt someone",
            "want to hurt",
            "going to hurt him",
            "going to hurt her",
            "le tuer",
            "la tuer",
            "les tuer",
            "tuer quelqu'un",
            "faire du mal a quelqu'un",
            "envie de frapper",
            "lui faire du mal"));
    PHRASES.put(
        Category.SUBSTANCE_CRISIS,
        List.of(
            "overdose",
            "overdosed",
            "took too many pills",
            "too many pills",
            "took all my pills",
            "can't stop drinking",
            "cant stop drinking",
            "drinking all day",
            "surdose",
            "trop de comprimes",
            "trop de medicaments",
            "tous mes medicaments",
            "je bois toute la journee",
            "je n'arrive pas a arreter de boire"));
    PHRASES.put(
        Category.RISK_TAKING,
        List.of(
            "don't care if i die",
            "dont care if i die",
            "do not care if i die",
            "don't care if i live",
            "drive into",
            "jump off",
            "peu importe si je meurs",
            "je me fiche de mourir",
            "je me fous de mourir",
            "sauter du pont",
            "me jeter"));
    PHRASES.put(
        Category.ACUTE_ANXIETY,
        List.of(
            "panic attack",
            "can't breathe",
            "cant breathe",
            "cannot breathe",
            "heart is racing",
            "losing my mind",
            "going crazy",
            "terrified all the time",
            "crise de panique",
            "crise d'angoisse",
            "n'arrive plus a respirer",
            "j'etouffe",
            "je deviens fou",
            "je deviens folle",
            "angoisse permanente"));
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public Optional<Category> classify(String text, Locale locale) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String plain = plain(text);
    for (var entry : PHRASES.entrySet()) {
      for (String phrase : entry.getValue()) {
        if (plain.contains(plain(phrase))) {
          return Optional.of(entry.getKey());
        }
      }
    }
    return Optional.empty();
  }

  /** Lower case, no accents, words split at apostrophes and punctuation, single spaces. */
  static String plain(String text) {
    return " "
        + Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('’', '\'')
            .replace('‘', '\'')
            // French elisions (d'en, j'ai) and English contractions split into words.
            .replaceAll("[^\\p{L}\\p{N}-]+", " ")
            .replaceAll("\\s+", " ")
            .strip()
        + " ";
  }
}
