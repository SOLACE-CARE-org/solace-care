package care.solace.core.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public reference data (contracts/openapi/core.yaml, tag catalogue), in the caller's language. */
@RestController
class CatalogueController {

  private final Catalogue catalogue;

  CatalogueController(Catalogue catalogue) {
    this.catalogue = catalogue;
  }

  @GetMapping("/v1/catalogue/session-languages")
  List<SessionLanguage> sessionLanguages(Locale locale) {
    return catalogue.sessionLanguages(locale).stream()
        .map(e -> new SessionLanguage(e.code(), e.label()))
        .toList();
  }

  @GetMapping("/v1/catalogue/help-topics")
  List<HelpTopic> helpTopics(Locale locale) {
    return catalogue.helpTopics(locale).stream()
        .map(e -> new HelpTopic(e.code(), e.label()))
        .toList();
  }

  /** Each profession with the specialties it allows, in catalogue order (D62). */
  @GetMapping("/v1/catalogue/professions")
  List<Profession> professions(Locale locale) {
    var tree = catalogue.specialtyTree(locale);
    return catalogue.professions(locale).stream()
        .map(
            e -> {
              var allowed = catalogue.allowedSpecialties(e.code());
              return new Profession(
                  e.code(),
                  e.label(),
                  tree.stream()
                      .filter(s -> allowed.contains(s.code()))
                      .map(CatalogueController::specialty)
                      .toList());
            })
        .toList();
  }

  @GetMapping("/v1/catalogue/specialties")
  List<Specialty> specialties(Locale locale) {
    return catalogue.specialtyTree(locale).stream().map(CatalogueController::specialty).toList();
  }

  private static Specialty specialty(Catalogue.Specialty s) {
    return new Specialty(s.code(), s.label(), s.parentCode());
  }

  record SessionLanguage(String code, String name) {}

  record HelpTopic(String code, String label) {}

  record Profession(String code, String label, List<Specialty> specialties) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Specialty(String code, String label, String parentCode) {}
}
