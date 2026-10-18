package care.solace.core.content;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Published content for the apps (tag content; D44), open without a token: legal texts are read
 * before sign-up and crisis resources by guests. Only published versions leave Core.
 */
@RestController
class ContentController {

  private static final Pattern TYPE = Pattern.compile("CRISIS_RESOURCE|ARTICLE|FAQ|LEGAL");

  private final Contents contents;

  ContentController(Contents contents) {
    this.contents = contents;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Published(
      String slug,
      String type,
      String version,
      String language,
      String title,
      String body,
      String country,
      List<ContentStore.Dial> numbers,
      Instant publishedAt) {}

  @GetMapping("/v1/content")
  ResponseEntity<List<Published>> list(
      @RequestParam String type, @RequestParam(required = false) String country, Locale locale) {
    if (!TYPE.matcher(type).matches()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var items =
        contents.all(type).stream()
            .filter(i -> country == null || country.equals(i.country()))
            .map(i -> published(i, locale))
            .flatMap(java.util.Optional::stream)
            .toList();
    return cached(items);
  }

  @GetMapping("/v1/content/{slug}")
  ResponseEntity<Published> one(@PathVariable String slug, Locale locale) {
    return cached(
        contents.all(null).stream()
            .filter(i -> i.slug().equals(slug))
            .findFirst()
            .flatMap(i -> published(i, locale))
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND)));
  }

  private java.util.Optional<Published> published(ContentStore.Item item, Locale locale) {
    boolean fr = "fr".equals(locale.getLanguage());
    return contents
        .published(item.id())
        .map(
            v ->
                new Published(
                    item.slug(),
                    item.type(),
                    "v" + v.version(),
                    fr ? "fr" : "en",
                    fr ? v.text().titleFr() : v.text().titleEn(),
                    fr ? v.text().bodyFr() : v.text().bodyEn(),
                    item.country(),
                    item.type().equals("CRISIS_RESOURCE") ? v.text().numbers() : null,
                    v.publishedAt()));
  }

  private static <T> ResponseEntity<T> cached(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(body);
  }
}
