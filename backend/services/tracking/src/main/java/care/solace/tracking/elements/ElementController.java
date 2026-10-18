package care.solace.tracking.elements;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The element library for the phone's offline cache (tag elements). */
@RestController
class ElementController {

  private final ElementLibrary library;

  ElementController(ElementLibrary library) {
    this.library = library;
  }

  record ElementView(
      String code,
      String version,
      String kind,
      String valueType,
      JsonNode constraints,
      String inputView,
      String icon,
      List<String> signals,
      JsonNode labels,
      String minAppVersion) {}

  static ElementView view(ElementLibrary.Element e) {
    return new ElementView(
        e.code(),
        e.version(),
        e.kind().name(),
        e.valueType().name(),
        e.constraints(),
        e.inputView(),
        e.icon(),
        e.signals(),
        e.labels(),
        e.minAppVersion());
  }

  @GetMapping("/v1/tracking/elements")
  ResponseEntity<List<ElementView>> elements() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .body(library.all().stream().map(ElementController::view).toList());
  }
}
