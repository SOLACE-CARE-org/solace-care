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

  Offers(
      DirectoryStore directory,
      ApplicationStore applications,
      Catalogue catalogue,
      SpecialistSettings settings,
      LivePricing pricing) {
    this.directory = directory;
    this.applications = applications;
    this.catalogue = catalogue;
    this.settings = settings;
    this.pricing = pricing;
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
