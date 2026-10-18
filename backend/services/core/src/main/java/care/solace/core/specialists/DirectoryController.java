package care.solace.core.specialists;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/catalogue/specialists} (contracts/openapi/core.yaml, tag catalogue). Open without a
 * token, so guests can browse; it shows only what a listed profile shows.
 */
@RestController
class DirectoryController {

  private static final Set<String> MODALITIES = Set.of("AUDIO", "VIDEO");

  private final Directory directory;

  DirectoryController(Directory directory) {
    this.directory = directory;
  }

  record Offering(String modality, int durationMinutes, long price) {}

  record Language(String code, String level) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Card(
      UUID id,
      String fullName,
      String professionLabel,
      List<String> focusAreas,
      List<Language> languages,
      Offering offering,
      Instant nextAvailable) {}

  record Page(List<Card> items, boolean hasMore) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Profile(
      UUID id,
      String fullName,
      String professionLabel,
      String specialty,
      String bio,
      List<Language> languages,
      List<Offering> offerings,
      List<String> focusAreas,
      Instant nextAvailable) {}

  @GetMapping("/v1/catalogue/specialists")
  Page search(
      @RequestParam String language,
      @RequestParam(required = false) String modality,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String specialty,
      @RequestParam(required = false) Long maxPrice,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Locale locale) {
    if ((modality != null && !MODALITIES.contains(modality))
        || (q != null && q.length() > 120)
        || (maxPrice != null && maxPrice < 0)
        || limit < 1
        || limit > 50
        || offset < 0) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var page =
        directory.search(
            new Directory.Query(
                language, modality, q, blankToNull(specialty), maxPrice, limit, offset),
            locale);
    return new Page(
        page.items().stream()
            .map(
                c ->
                    new Card(
                        c.id(),
                        c.fullName(),
                        c.professionLabel(),
                        c.focusAreas(),
                        languages(c.languages()),
                        offering(c.offering()),
                        c.nextAvailable()))
            .toList(),
        page.hasMore());
  }

  @GetMapping("/v1/catalogue/specialists/{id}")
  Profile profile(@PathVariable UUID id, Locale locale) {
    var p = directory.profile(id, locale);
    return new Profile(
        p.id(),
        p.fullName(),
        p.professionLabel(),
        p.specialty(),
        p.bio(),
        languages(p.languages()),
        p.offerings().stream().map(DirectoryController::offering).toList(),
        p.focusAreas(),
        p.nextAvailable());
  }

  private static List<Language> languages(List<DirectoryStore.Language> languages) {
    return languages.stream().map(l -> new Language(l.code(), l.level())).toList();
  }

  private static Offering offering(DirectoryStore.Offer o) {
    return new Offering(o.modality(), o.durationMinutes(), o.price());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
