package care.solace.core.specialists;

import care.solace.core.catalog.Catalogue;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link SpecialistOffers} over the directory's listing rules. */
@Service
class Offers implements SpecialistOffers {

  private final DirectoryStore directory;
  private final ApplicationStore applications;
  private final Catalogue catalogue;
  private final SpecialistSettings settings;
  private final LivePricing pricing;
  private final org.springframework.beans.factory.ObjectProvider<Directory> search;

  Offers(
      DirectoryStore directory,
      ApplicationStore applications,
      Catalogue catalogue,
      SpecialistSettings settings,
      LivePricing pricing,
      org.springframework.beans.factory.ObjectProvider<Directory> search) {
    this.directory = directory;
    this.applications = applications;
    this.catalogue = catalogue;
    this.settings = settings;
    this.pricing = pricing;
    this.search = search;
  }

  @Override
  public java.util.List<Match> matches(
      java.util.Collection<String> specialties,
      java.util.Collection<String> languages,
      Locale viewer,
      int limit) {
    java.util.Map<UUID, Match> found = new java.util.LinkedHashMap<>();
    for (String language : languages) {
      for (String specialty : specialties) {
        var page =
            search
                .getObject()
                .search(
                    new Directory.Query(language, null, null, specialty, null, null, limit, 0),
                    viewer);
        for (var card : page.items()) {
          found.putIfAbsent(
              card.id(),
              new Match(
                  card.id(),
                  card.fullName(),
                  card.professionLabel(),
                  card.languages().stream().map(DirectoryStore.Language::code).toList(),
                  card.nextAvailable()));
        }
      }
    }
    return found.values().stream()
        .sorted(
            java.util.Comparator.comparing(
                Match::nextAvailable,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
        .limit(limit)
        .toList();
  }

  @Override
  public java.util.List<Candidate> candidates(
      java.util.Collection<String> specialties,
      java.util.Collection<String> languages,
      String modality,
      Long maxPrice,
      Locale viewer,
      int limit) {
    java.util.Map<UUID, Candidate> found = new java.util.LinkedHashMap<>();
    for (String specialty : specialties) {
      for (String language : languages) {
        var page =
            search
                .getObject()
                .search(
                    new Directory.Query(
                        language, modality, null, specialty, maxPrice, null, limit, 0),
                    viewer);
        for (var card : page.items()) {
          found.putIfAbsent(
              card.id(),
              new Candidate(
                  card.id(),
                  card.fullName(),
                  card.professionLabel(),
                  specialty,
                  card.languages().stream().map(DirectoryStore.Language::code).toList(),
                  directory.focusCodes(card.id()),
                  card.nextAvailable(),
                  card.offering() == null ? 0 : card.offering().price(),
                  card.rating() == null ? 0 : card.rating().average()));
        }
      }
    }
    return java.util.List.copyOf(found.values());
  }

  @Override
  public java.math.BigDecimal feePercent() {
    return pricing.current().feePercent();
  }

  @Override
  public Optional<PayoutDetails> payoutDetails(UUID specialistId) {
    return applications
        .payout(specialistId)
        .filter(p -> p.method().equals("MTN_MOMO") || p.method().equals("ORANGE_MONEY"))
        .map(
            p ->
                new PayoutDetails(
                    p.method(),
                    p.phone(),
                    applications.payoutSavedAt(specialistId).orElse(java.time.Instant.EPOCH)));
  }

  @Override
  public Optional<Offer> offer(UUID specialistId, String modality) {
    if (directory.listed(specialistId).isEmpty()) {
      return Optional.empty();
    }
    return directory.offers(specialistId).stream()
        .filter(o -> o.modality().equals(modality))
        .findFirst()
        .map(o -> new Offer(o.modality(), o.durationMinutes(), o.price()));
  }

  @Override
  public boolean bookableIn(UUID specialistId, String language) {
    return directory.listed(specialistId).isPresent()
        && directory.bookableLanguages(specialistId).stream()
            .anyMatch(l -> l.code().equals(language));
  }

  @Override
  public Optional<Summary> summary(UUID specialistId, Locale viewer) {
    return applications
        .find(specialistId)
        .map(
            p ->
                new Summary(
                    p.fullName(),
                    p.profession() == null
                        ? null
                        : catalogue.allProfessions(viewer).stream()
                            .filter(e -> e.code().equals(p.profession()))
                            .map(Catalogue.Entry::label)
                            .findFirst()
                            .orElse(null)));
  }
}
