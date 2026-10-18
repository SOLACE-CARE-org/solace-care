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

  Offers(DirectoryStore directory, ApplicationStore applications, Catalogue catalogue) {
    this.directory = directory;
    this.applications = applications;
    this.catalogue = catalogue;
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
