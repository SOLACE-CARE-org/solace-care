package care.solace.lists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import care.solace.errors.SolaceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A reviewed list (D61): the code's list is in force first; a version goes live only when a
 * different clinician signs it off after its check passed, and is then in force.
 */
@SpringBootTest(classes = ReviewedListsIntegrationTest.App.class)
@Testcontainers
class ReviewedListsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicReference<JsonNode> IN_FORCE = new AtomicReference<>();

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {
    @Bean
    ListAccess access() {
      return token -> new ReviewedLists.Staff("x", "X", List.of("SUPER_ADMIN"));
    }

    @Bean
    ReviewedList words() {
      return new ReviewedList() {
        @Override
        public String code() {
          return "test.words";
        }

        @Override
        public String description() {
          return "Words";
        }

        @Override
        public JsonNode defaults() {
          return JSON.valueToTree(List.of("diagnosis"));
        }

        @Override
        public void validate(JsonNode entries) {
          if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalArgumentException("a list of words");
          }
        }

        @Override
        public Optional<String> check(JsonNode entries) {
          // Stands in for the safety set: the list must keep "diagnosis".
          for (JsonNode e : entries) {
            if (e.asText().equals("diagnosis")) {
              return Optional.empty();
            }
          }
          return Optional.of("diagnosis is missing");
        }

        @Override
        public void apply(JsonNode live, int version) {
          IN_FORCE.set(live);
        }
      };
    }
  }

  @Autowired ReviewedLists lists;

  private static final ReviewedLists.Staff DRAFTER =
      new ReviewedLists.Staff("cr-1", "Dr. A", List.of("CLINICAL_REVIEWER"));
  private static final ReviewedLists.Staff APPROVER =
      new ReviewedLists.Staff("cr-2", "Dr. B", List.of("CLINICAL_REVIEWER"));
  private static final ReviewedLists.Staff SUPPORT =
      new ReviewedLists.Staff("su-1", "S", List.of("SUPPORT"));

  @Test
  void aVersionGoesLiveOnlyWithACheckAndASecondClinician() {
    lists.reload();
    assertThat(IN_FORCE.get()).isEqualTo(JSON.valueToTree(List.of("diagnosis")));

    assertThatThrownBy(
            () -> lists.draft("test.words", JSON.valueToTree(List.of()), "Empty", DRAFTER))
        .isInstanceOf(SolaceException.class);
    assertThatThrownBy(
            () -> lists.draft("test.words", JSON.valueToTree(List.of("x")), "No role", SUPPORT))
        .isInstanceOf(SolaceException.class);

    var failing = lists.draft("test.words", JSON.valueToTree(List.of("triage")), "Swap", DRAFTER);
    assertThat(lists.check("test.words", failing.version())).contains("diagnosis is missing");
    assertThatThrownBy(() -> lists.signOff("test.words", failing.version(), APPROVER))
        .isInstanceOf(SolaceException.class);
    lists.decline("test.words", failing.version(), APPROVER);

    var good =
        lists.draft(
            "test.words", JSON.valueToTree(List.of("diagnosis", "triage")), "Add one", DRAFTER);
    // Whoever drafted it cannot sign it off.
    assertThatThrownBy(() -> lists.signOff("test.words", good.version(), DRAFTER))
        .isInstanceOf(SolaceException.class);
    var live = lists.signOff("test.words", good.version(), APPROVER);

    assertThat(live.status()).isEqualTo(ReviewedLists.Status.LIVE);
    assertThat(live.decidedByName()).isEqualTo("Dr. B");
    assertThat(IN_FORCE.get()).isEqualTo(JSON.valueToTree(List.of("diagnosis", "triage")));
    assertThat(lists.versions("test.words")).hasSize(2);
  }
}
