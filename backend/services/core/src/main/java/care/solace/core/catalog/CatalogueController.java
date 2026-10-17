package care.solace.core.catalog;

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

  record SessionLanguage(String code, String name) {}

  record HelpTopic(String code, String label) {}
}
