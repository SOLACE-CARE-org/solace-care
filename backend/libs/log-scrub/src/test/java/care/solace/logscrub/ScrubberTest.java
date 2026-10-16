package care.solace.logscrub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScrubberTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Sending code to +237677000000 now | Sending code to [phone] now",
        "Sending code to +237 6 77 00 00 00 now | Sending code to [phone] now",
        "Guest phone 677000000 verified | Guest phone [phone] verified",
        "Guest phone 6 77 00 00 00 verified | Guest phone [phone] verified",
        "Mail to awa.ngono@example.cm failed | Mail to [email] failed",
        "Authorization: Bearer abc.def.ghi | Authorization: [redacted] [redacted]",
        "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig seen | token [token] seen",
        "login code=123456 for user 42 | login code=[redacted] for user 42",
        "password: hunter2, retry | password: [redacted], retry",
      })
  void removesPersonalData(String input, String expected) {
    assertThat(Scrubber.scrub(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"note\":\"I felt very low today\",\"mood\":2}",
        "{\"text\": \"great session, thank you \\\"doc\\\"\"}",
        "{\"answers\":\"yes\"}",
        "{\"phone_number\":\"+237677000000\"}",
      })
  void removesFreeTextFieldsFromJson(String json) {
    String scrubbed = Scrubber.scrub(json);

    assertThat(scrubbed).contains("[redacted]");
    assertThat(scrubbed)
        .doesNotContain("low today")
        .doesNotContain("great session")
        .doesNotContain("677000000");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Booking 3f2a9c1e-7b4d-4e8a-9c2b-1d5e6f7a8b9c confirmed",
        "Slot hold expired after 600000 ms",
        "errorCode=booking.slot-taken status=409",
        "Took 1234 ms for 6 rows",
        "traceId=4bf92f3577b34da6a3ce929d0e0e4736",
        "Amount 15000 XAF",
      })
  void leavesOrdinaryOperationalTextAlone(String text) {
    assertThat(Scrubber.scrub(text)).isEqualTo(text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"email", "Phone_Number", "NOTE", "password"})
  void structuredSensitiveFieldsAreRemovedWhole(String field) {
    assertThat(Scrubber.scrubField(field, "anything at all")).isEqualTo(Scrubber.REDACTED);
  }
}
