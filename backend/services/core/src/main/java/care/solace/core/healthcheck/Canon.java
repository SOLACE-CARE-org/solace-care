package care.solace.core.healthcheck;

import care.solace.core.healthcheck.Definition.Kind;
import care.solace.core.healthcheck.Definition.Option;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Definition.Rule;
import care.solace.core.healthcheck.Definition.ShowIf;
import care.solace.core.healthcheck.Definition.TestSet;
import care.solace.core.healthcheck.Definition.When;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What Solace fixes in every version (System Design, Health check; D55): the safety question and
 * the PHQ-9 and GAD-7 items in their published English and French wording, with their 0 to 3
 * answers. Nobody edits these; a version that changes them is refused. Also the v1 draft that the
 * clinical team reviews before launch.
 */
final class Canon {

  private Canon() {}

  static final String SAFETY = "safety";
  static final String PHQ9 = "PHQ9";
  static final String GAD7 = "GAD7";

  /** Published cut-offs (PHQ-9: 5, 10, 15, 20; GAD-7: 5, 10, 15). Fixed, not settings. */
  static String band(String screener, int total) {
    if (PHQ9.equals(screener)) {
      return total >= 20
          ? "SEVERE"
          : total >= 15
              ? "MODERATELY_SEVERE"
              : total >= 10 ? "MODERATE" : total >= 5 ? "MILD" : "MINIMAL";
    }
    return total >= 15 ? "SEVERE" : total >= 10 ? "MODERATE" : total >= 5 ? "MILD" : "MINIMAL";
  }

  private static final List<Option> FREQUENCY =
      List.of(
          new Option("not_at_all", "Not at all", "Jamais", 0, null),
          new Option("several_days", "Several days", "Plusieurs jours", 1, null),
          new Option(
              "more_than_half", "More than half the days", "Plus de la moitié du temps", 2, null),
          new Option("nearly_every_day", "Nearly every day", "Presque tous les jours", 3, null));

  private static final String LEAD_EN =
      "Over the last 2 weeks, how often have you been bothered by:";
  private static final String LEAD_FR =
      "Au cours des 2 dernières semaines, selon quelle fréquence avez-vous été gêné(e) par :";

  private static final String[][] PHQ9_ITEMS = {
    {
      "Little interest or pleasure in doing things",
      "Peu d'intérêt ou de plaisir à faire les choses"
    },
    {"Feeling down, depressed, or hopeless", "Être triste, déprimé(e) ou désespéré(e)"},
    {
      "Trouble falling or staying asleep, or sleeping too much",
      "Difficultés à s'endormir ou à rester endormi(e), ou dormir trop"
    },
    {"Feeling tired or having little energy", "Se sentir fatigué(e) ou manquer d'énergie"},
    {"Poor appetite or overeating", "Avoir peu d'appétit ou manger trop"},
    {
      "Feeling bad about yourself, or that you are a failure or have let yourself or your family down",
      "Avoir une mauvaise opinion de soi-même, ou avoir le sentiment d'être nul(le), ou d'avoir déçu"
          + " sa famille ou s'être déçu(e) soi-même"
    },
    {
      "Trouble concentrating on things, such as reading the newspaper or watching television",
      "Avoir du mal à se concentrer, par exemple pour lire le journal ou regarder la télévision"
    },
    {
      "Moving or speaking so slowly that other people could have noticed, or the opposite: being so"
          + " fidgety or restless that you have been moving around a lot more than usual",
      "Bouger ou parler si lentement que les autres auraient pu le remarquer, ou au contraire être si"
          + " agité(e) que vous avez eu du mal à tenir en place par rapport à d'habitude"
    },
    {
      "Thoughts that you would be better off dead, or of hurting yourself in some way",
      "Penser qu'il vaudrait mieux mourir ou envisager de vous faire du mal d'une manière ou d'une"
          + " autre"
    }
  };

  private static final String[][] GAD7_ITEMS = {
    {"Feeling nervous, anxious or on edge", "Un sentiment de nervosité, d'anxiété ou de tension"},
    {
      "Not being able to stop or control worrying",
      "Une incapacité à arrêter de s'inquiéter ou à contrôler ses inquiétudes"
    },
    {
      "Worrying too much about different things",
      "Une inquiétude excessive à propos de différentes choses"
    },
    {"Trouble relaxing", "Des difficultés à se détendre"},
    {
      "Being so restless that it is hard to sit still",
      "Une agitation telle qu'il est difficile de rester tranquille"
    },
    {
      "Becoming easily annoyed or irritable",
      "Une tendance à être facilement contrarié(e) ou irritable"
    },
    {
      "Feeling afraid, as if something awful might happen",
      "Un sentiment de peur comme si quelque chose de terrible risquait de se produire"
    }
  };

  /** The safety question; any answer but "no" opens urgent help. */
  static Question safety() {
    return new Question(
        SAFETY,
        Kind.SAFETY,
        false,
        2,
        "In the last 2 weeks, have you had thoughts that you would be better off dead, or of"
            + " hurting yourself in some way?",
        "Au cours des 2 dernières semaines, avez-vous pensé qu'il vaudrait mieux mourir, ou à vous"
            + " faire du mal d'une manière ou d'une autre ?",
        null,
        null,
        null,
        null,
        true,
        List.of(
            new Option("no", "No", "Non", null, null),
            new Option("yes_sometimes", "Yes, sometimes", "Oui, parfois", null, null),
            new Option("yes_often", "Yes, often", "Oui, souvent", null, null),
            new Option(
                "rather_not_say", "I'd rather not say", "Je préfère ne pas répondre", null, null)),
        null);
  }

  static List<Question> screener(String screener) {
    String[][] items = PHQ9.equals(screener) ? PHQ9_ITEMS : GAD7_ITEMS;
    String prefix = PHQ9.equals(screener) ? "phq9_" : "gad7_";
    List<Question> out = new ArrayList<>();
    for (int i = 0; i < items.length; i++) {
      int item = i + 1;
      // The first two items are PHQ-2 or GAD-2; the rest follow when those reach 3.
      ShowIf showIf = item <= 2 ? null : new ShowIf(null, null, screener, 2, 3);
      out.add(
          new Question(
              prefix + item,
              Kind.SCREENER,
              false,
              2,
              items[i][0],
              items[i][1],
              LEAD_EN,
              LEAD_FR,
              screener,
              item,
              true,
              FREQUENCY,
              showIf));
    }
    return out;
  }

  /** Every locked question, as it must appear in any version. */
  static Map<String, Question> locked() {
    Map<String, Question> all = new java.util.LinkedHashMap<>();
    all.put(SAFETY, safety());
    for (Question q : screener(PHQ9)) {
      all.put(q.code(), q);
    }
    for (Question q : screener(GAD7)) {
      all.put(q.code(), q);
    }
    return all;
  }

  // --- The v1 draft (System Design, Health check, What the seeker answers) ----------------------

  static List<Question> v1Questions() {
    List<Question> q = new ArrayList<>();
    q.add(
        choice(
            "topics",
            true,
            1,
            "What would you like help with?",
            "Pour quoi souhaitez-vous de l'aide ?",
            "Choose all that apply.",
            "Choisissez tout ce qui s'applique.",
            List.of(
                new Option("worry", "Worry or anxiety", "Inquiétude ou anxiété", null, "anxiety"),
                new Option("low_mood", "Low mood", "Moral bas", null, "low-mood"),
                new Option(
                    "stress",
                    "Stress at work or school",
                    "Stress au travail ou à l'école",
                    null,
                    "work-stress"),
                new Option("sleep", "Sleep", "Sommeil", null, "sleep"),
                new Option(
                    "relationships",
                    "Relationships or family",
                    "Couple ou famille",
                    null,
                    "relationships"),
                new Option("grief", "Grief or loss", "Deuil ou perte", null, "grief"),
                new Option(
                    "hard_experience",
                    "A hard or frightening experience",
                    "Une expérience difficile ou effrayante",
                    null,
                    null),
                new Option("substances", "Alcohol or drugs", "Alcool ou drogues", null, null),
                new Option(
                    "hormones", "Cycle or hormones", "Cycle ou hormones", null, "womens-health"),
                new Option("other", "Something else", "Autre chose", null, null))));
    q.add(
        choice(
            "duration",
            false,
            1,
            "How long has this been going on?",
            "Depuis combien de temps cela dure-t-il ?",
            null,
            null,
            List.of(
                new Option(
                    "under_two_weeks", "Less than 2 weeks", "Moins de 2 semaines", null, null),
                new Option(
                    "two_weeks_to_month",
                    "2 weeks to a month",
                    "De 2 semaines à un mois",
                    null,
                    null),
                new Option("one_to_six_months", "1 to 6 months", "De 1 à 6 mois", null, null),
                new Option(
                    "over_six_months", "More than 6 months", "Plus de 6 mois", null, null))));
    q.add(
        choice(
            "impact",
            false,
            1,
            "How much does it affect your daily life, such as work, school or family?",
            "À quel point cela affecte-t-il votre vie quotidienne, comme le travail, l'école ou la"
                + " famille ?",
            null,
            null,
            List.of(
                new Option("not_much", "Not much", "Pas beaucoup", null, null),
                new Option("some_days", "Some days", "Certains jours", null, null),
                new Option("most_days", "Most days", "La plupart des jours", null, null),
                new Option("every_day", "Every day", "Tous les jours", null, null))));
    q.add(safety());
    List<Question> phq = screener(PHQ9);
    List<Question> gad = screener(GAD7);
    q.addAll(phq.subList(0, 2));
    q.addAll(gad.subList(0, 2));
    q.addAll(phq.subList(2, phq.size()));
    q.addAll(gad.subList(2, gad.size()));
    q.add(
        choice(
            "medicine",
            false,
            2,
            "Do you take, or think you may need, medicine for your mental health?",
            "Prenez-vous, ou pensez-vous avoir besoin, d'un traitement médicamenteux pour votre santé"
                + " mentale ?",
            null,
            null,
            List.of(
                new Option("taking", "Yes, I take some", "Oui, j'en prends", null, null),
                new Option(
                    "may_need", "I think I may need it", "Je pense en avoir besoin", null, null),
                new Option("no", "No", "Non", null, null),
                new Option("not_sure", "Not sure", "Je ne sais pas", null, null))));
    return q;
  }

  /** The v1 rules, at placeholder points the clinical team sets before launch. */
  static List<Rule> v1Rules() {
    return List.of(
        new Rule(
            "worry.daily_life",
            List.of(answer("topics", "worry"), answer("impact", "most_days", "every_day")),
            "clinical-psychology",
            2,
            "You said worry is affecting your daily life most days.",
            "Vous avez dit que l'inquiétude gêne votre vie quotidienne presque tous les jours."),
        new Rule(
            "long_lasting",
            List.of(answer("duration", "one_to_six_months", "over_six_months")),
            "clinical-psychology",
            1,
            "This has lasted more than a month.",
            "Cela dure depuis plus d'un mois."),
        new Rule(
            "gad7.above_everyday",
            List.of(new When(null, null, GAD7, 10, null)),
            "clinical-psychology",
            2,
            "Your answers on worry are above everyday stress, where talking therapy helps.",
            "Vos réponses sur l'inquiétude dépassent le stress ordinaire : une thérapie par la parole"
                + " peut aider."),
        new Rule(
            "phq9.above_everyday",
            List.of(new When(null, null, PHQ9, 10, null)),
            "clinical-psychology",
            2,
            "Your answers on mood are above everyday ups and downs, where talking therapy helps.",
            "Vos réponses sur le moral dépassent les hauts et les bas ordinaires : une thérapie par la"
                + " parole peut aider."),
        new Rule(
            "phq9.high",
            List.of(new When(null, null, PHQ9, 20, null)),
            "psychiatry",
            3,
            "A specialist who can also look at medicine may help with what you described.",
            "Un spécialiste qui peut aussi envisager un traitement peut aider pour ce que vous"
                + " décrivez."),
        new Rule(
            "medicine",
            List.of(answer("medicine", "taking", "may_need")),
            "psychiatry",
            2,
            "You said you take, or may need, medicine for your mental health.",
            "Vous avez dit prendre, ou avoir peut-être besoin, d'un traitement pour votre santé"
                + " mentale."),
        new Rule(
            "stress.work",
            List.of(answer("topics", "stress")),
            "counselling",
            2,
            "You said stress at work or school is part of it.",
            "Vous avez dit que le stress au travail ou à l'école y contribue."),
        new Rule(
            "relationships",
            List.of(answer("topics", "relationships", "grief")),
            "counselling",
            2,
            "You said relationships, family or a loss are part of it.",
            "Vous avez dit que le couple, la famille ou une perte y contribuent."),
        new Rule(
            "recent",
            List.of(answer("duration", "under_two_weeks")),
            "counselling",
            1,
            "It started recently, and practical support often helps early on.",
            "Cela a commencé récemment, et un soutien concret aide souvent dès le début."));
  }

  static List<String> v1SpecialtyOrder() {
    return List.of("clinical-psychology", "counselling", "psychiatry");
  }

  /** Made-up answer sets; never real answers. */
  static List<TestSet> v1TestSets() {
    return List.of(
        new TestSet(
            "Worry at work, most days, for months",
            Map.of(
                "topics", List.of("worry", "stress"),
                "duration", List.of("one_to_six_months"),
                "impact", List.of("most_days"),
                "safety", List.of("no"),
                "phq9_1", List.of("several_days"),
                "phq9_2", List.of("not_at_all"),
                "gad7_1", List.of("nearly_every_day"),
                "gad7_2", List.of("more_than_half"),
                "medicine", List.of("no")),
            "clinical-psychology"),
        new TestSet(
            "Recent stress at work",
            Map.of(
                "topics", List.of("stress"),
                "duration", List.of("under_two_weeks"),
                "impact", List.of("some_days"),
                "safety", List.of("no"),
                "phq9_1", List.of("not_at_all"),
                "phq9_2", List.of("not_at_all"),
                "gad7_1", List.of("several_days"),
                "gad7_2", List.of("not_at_all"),
                "medicine", List.of("no")),
            "counselling"),
        new TestSet(
            "Asks about medicine",
            Map.of(
                "topics", List.of("low_mood"),
                "duration", List.of("over_six_months"),
                "impact", List.of("some_days"),
                "safety", List.of("no"),
                "phq9_1", List.of("not_at_all"),
                "phq9_2", List.of("several_days"),
                "gad7_1", List.of("not_at_all"),
                "gad7_2", List.of("not_at_all"),
                "medicine", List.of("may_need")),
            "psychiatry"),
        new TestSet(
            "Safety answer",
            Map.of("topics", List.of("low_mood"), "safety", List.of("yes_sometimes")),
            null));
  }

  private static Question choice(
      String code,
      boolean multi,
      int step,
      String en,
      String fr,
      String introEn,
      String introFr,
      List<Option> options) {
    return new Question(
        code, Kind.CHOICE, multi, step, en, fr, introEn, introFr, null, null, false, options, null);
  }

  private static When answer(String question, String... anyOf) {
    return new When(question, List.of(anyOf), null, null, null);
  }
}
