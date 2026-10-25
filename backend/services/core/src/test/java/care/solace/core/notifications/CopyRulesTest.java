package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** What an edited message must keep (D61), checked against the approved wording. */
class CopyRulesTest {

  private final Copy copy = new Copy();

  private Copy.Approved approved(String key) {
    return copy.approvedCopy().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
  }

  @Test
  void theApprovedWordingPassesItsOwnRules() {
    for (var a : copy.approvedCopy()) {
      assertThat(CopyRules.check(a.key(), a.en(), a.en(), Locale.ENGLISH)).as(a.key()).isEmpty();
      assertThat(CopyRules.check(a.key(), a.fr(), a.fr(), Locale.FRENCH)).as(a.key()).isEmpty();
    }
  }

  @Test
  void anEditKeepsThePlaceholders() {
    String approved = approved("push.booking-confirmed").en();
    assertThat(
            CopyRules.check("push.booking-confirmed", "Booked for {0}.", approved, Locale.ENGLISH))
        .contains(CopyRules.Problem.PLACEHOLDERS);
    assertThat(
            CopyRules.check(
                "push.booking-confirmed", "Booked: {0}, {1}, {2}.", approved, Locale.ENGLISH))
        .contains(CopyRules.Problem.PLACEHOLDERS);
    assertThat(
            CopyRules.check(
                "push.booking-confirmed", "You're booked: {0} at {1}.", approved, Locale.ENGLISH))
        .isEmpty();
  }

  @Test
  void anEditStaysNeutralForSeekersButNotForCrisisStaff() {
    String push = approved("push.day-before").en();
    assertThat(CopyRules.check("push.day-before", "Great news! " + push, push, Locale.ENGLISH))
        .contains(CopyRules.Problem.WORDING);
    assertThat(
            CopyRules.check(
                "push.day-before", push + " Your therapy session.", push, Locale.ENGLISH))
        .contains(CopyRules.Problem.WORDING);
    assertThat(CopyRules.check("push.day-before", push + " 😀", push, Locale.ENGLISH))
        .contains(CopyRules.Problem.WORDING);
    String category = approved("category.ACUTE_ANXIETY").en();
    assertThat(CopyRules.check("category.ACUTE_ANXIETY", category, category, Locale.ENGLISH))
        .isEmpty();
  }

  @Test
  void anSmsStaysInOneStandardMessage() {
    String approved = approved("sms.rescheduled").fr();
    assertThat(
            CopyRules.check(
                "sms.rescheduled", approved.replace("{0}", "{0} (fête)"), approved, Locale.FRENCH))
        .contains(CopyRules.Problem.SMS_CHARACTERS);
    assertThat(
            CopyRules.check(
                "sms.rescheduled",
                approved + " Merci de votre confiance.",
                approved,
                Locale.FRENCH))
        .contains(CopyRules.Problem.SMS_TOO_LONG);
    assertThat(CopyRules.check("sms.rescheduled", "RDV le {0} a {1}.", approved, Locale.FRENCH))
        .isEmpty();
  }

  @Test
  void anApostropheIsWrittenOnce() {
    assertThat(CopyRules.toMessageFormat("You're booked: {0}")).isEqualTo("You''re booked: {0}");
    assertThat(CopyRules.fromMessageFormat("You''re booked: {0}")).isEqualTo("You're booked: {0}");
  }
}
