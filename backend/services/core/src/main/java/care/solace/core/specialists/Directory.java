package care.solace.core.specialists;

import care.solace.core.catalog.Catalogue;
import care.solace.core.scheduling.Availability;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Find a specialist and the public profile (Search and Specialist screens). The session language
 * comes first; results are ordered by how well they match the seeker's words, then by who can see
 * them soonest, then by name.
 */
@Service
class Directory {

  private final DirectoryStore store;
  private final Catalogue catalogue;
  private final Availability availability;
  private final Clock clock;

  Directory(DirectoryStore store, Catalogue catalogue, Availability availability, Clock clock) {
    this.store = store;
    this.catalogue = catalogue;
    this.availability = availability;
    this.clock = clock;
  }

  record Query(
      String language,
      String modality,
      String q,
      String specialty,
      Long maxPrice,
      int limit,
      int offset) {}

  record Card(
      UUID id,
      String fullName,
      String professionLabel,
      List<String> focusAreas,
      List<DirectoryStore.Language> languages,
      DirectoryStore.Offer offering,
      Instant nextAvailable) {}

  record Page(List<Card> items, boolean hasMore) {}

  record Profile(
      UUID id,
      String fullName,
      String professionLabel,
      String specialty,
      String bio,
      List<DirectoryStore.Language> languages,
      List<DirectoryStore.Offer> offerings,
      List<String> focusAreas,
      Instant nextAvailable) {}

  Page search(Query query, Locale viewer) {
    if (!catalogue.areSessionLanguages(List.of(query.language()))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String tsQuery = tsQuery(query.q());
    if (query.q() != null && !query.q().isBlank() && tsQuery == null) {
      return new Page(List.of(), false);
    }
    boolean french = french(viewer);
    Map<String, String> professions = labels(catalogue.allProfessions(viewer));
    Instant now = clock.instant();
    Comparator<Ranked> order =
        Comparator.<Ranked>comparingDouble(r -> -r.rank())
            .thenComparing(
                r -> r.card().nextAvailable(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> r.card().fullName());
    List<Card> all =
        store
            .search(
                query.language(), query.modality(), query.specialty(), query.maxPrice(), tsQuery)
            .stream()
            .map(l -> new Ranked(card(l, query, professions, french, now), l.rank()))
            .sorted(order)
            .map(Ranked::card)
            .toList();
    int from = Math.min(query.offset(), all.size());
    int to = Math.min(from + query.limit(), all.size());
    return new Page(all.subList(from, to), to < all.size());
  }

  Profile profile(UUID id, Locale viewer) {
    var listed = store.listed(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    boolean french = french(viewer);
    var offers = store.offers(id);
    int shortest = offers.stream().mapToInt(DirectoryStore.Offer::durationMinutes).min().orElse(0);
    return new Profile(
        id,
        listed.fullName(),
        labels(catalogue.allProfessions(viewer)).get(listed.profession()),
        listed.specialty(),
        french ? listed.bioFr() : listed.bioEn(),
        store.bookableLanguages(id),
        offers,
        store.focusAreas(id, french),
        availability.nextAvailable(id, clock.instant(), shortest).orElse(null));
  }

  private Card card(
      DirectoryStore.Listed l,
      Query query,
      Map<String, String> professions,
      boolean french,
      Instant now) {
    var offers = store.offers(l.id());
    // The requested format, else the cheaper session.
    DirectoryStore.Offer offer =
        offers.stream()
            .filter(o -> query.modality() == null || o.modality().equals(query.modality()))
            .filter(o -> query.maxPrice() == null || o.price() <= query.maxPrice())
            .min(Comparator.comparingLong(DirectoryStore.Offer::price))
            .orElse(offers.getFirst());
    return new Card(
        l.id(),
        l.fullName(),
        professions.get(l.profession()),
        store.focusAreas(l.id(), french),
        store.bookableLanguages(l.id()),
        offer,
        availability.nextAvailable(l.id(), now, offer.durationMinutes()).orElse(null));
  }

  /**
   * The seeker's words as a prefix query ("anx lo" finds "anxiety", "low mood"), accents and
   * punctuation dropped; null when nothing searchable is left.
   */
  static String tsQuery(String q) {
    if (q == null || q.isBlank()) {
      return null;
    }
    String plain =
        Normalizer.normalize(q.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .strip();
    if (plain.isEmpty()) {
      return null;
    }
    return Arrays.stream(plain.split(" "))
        .limit(8)
        .map(word -> word + ":*")
        .collect(Collectors.joining(" & "));
  }

  private record Ranked(Card card, double rank) {}

  private static Map<String, String> labels(List<Catalogue.Entry> entries) {
    return entries.stream()
        .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
  }

  private static boolean french(Locale viewer) {
    return "fr".equals(viewer.getLanguage());
  }
}
