package care.solace.errors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = ProblemAdviceTest.App.class)
@AutoConfigureMockMvc
class ProblemAdviceTest {

  @Autowired MockMvc mvc;

  @Test
  void solaceErrorCarriesCodeReferenceAndTitle() throws Exception {
    mvc.perform(get("/slot-taken"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:solace:error:booking.slot-taken"))
        .andExpect(jsonPath("$.code").value("booking.slot-taken"))
        .andExpect(jsonPath("$.reference").value("BKG-409"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("booking.slot-taken"));
  }

  @Test
  void commonTitlesAreInFrench() throws Exception {
    mvc.perform(get("/forbidden").header("Accept-Language", "fr-CM"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access.denied"))
        .andExpect(jsonPath("$.reference").value("AUTH-403"))
        .andExpect(jsonPath("$.title").value("Accès refusé"));
  }

  @Test
  void commonTitlesAreInEnglishByDefault() throws Exception {
    mvc.perform(get("/forbidden")).andExpect(jsonPath("$.title").value("No access"));
  }

  @Test
  void validationNamesFieldsButNeverTheirValues() throws Exception {
    mvc.perform(
            post("/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"\",\"author\":\"+237677000000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("request.invalid"))
        .andExpect(jsonPath("$.fields[0]").value("author"))
        .andExpect(jsonPath("$.fields[1]").value("text"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("677000000"))));
  }

  @Test
  void unexpectedErrorsHideTheirMessage() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal"))
        .andExpect(jsonPath("$.reference").value("SYS-500"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret detail"))));
  }

  @Test
  void unknownRoutesAreNotFoundProblems() throws Exception {
    mvc.perform(get("/no-such-thing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource.not-found"));
  }

  @Test
  void aMalformedIdIsTheCallersMistake() throws Exception {
    mvc.perform(get("/bookings/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("request.invalid"));
  }

  enum BookingError implements ErrorCode {
    SLOT_TAKEN;

    @Override
    public String code() {
      return "booking.slot-taken";
    }

    @Override
    public int status() {
      return 409;
    }

    @Override
    public String area() {
      return "BKG";
    }
  }

  record ReviewRequest(@NotBlank String text, @Size(max = 5) String author) {}

  @EnableAutoConfiguration
  @RestController
  static class App {

    @GetMapping("/slot-taken")
    String slotTaken() {
      throw new SolaceException(BookingError.SLOT_TAKEN);
    }

    @GetMapping("/forbidden")
    String forbidden() {
      throw new AccessDeniedException("no");
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("secret detail about +237677000000");
    }

    @PostMapping("/reviews")
    String review(@Valid @RequestBody ReviewRequest request) {
      return "ok";
    }

    @GetMapping("/bookings/{id}")
    String booking(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
      return "ok";
    }
  }
}
