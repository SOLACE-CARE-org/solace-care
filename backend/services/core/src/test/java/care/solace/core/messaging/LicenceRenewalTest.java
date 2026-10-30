package care.solace.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.core.specialists.SpecialistFlowTest;
import care.solace.events.Events;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The licence renewal reminder, 60 days before, once per licence date (notification-copy.md). */
class LicenceRenewalTest extends SpecialistFlowTest {

  @Autowired Messages messages;
  @Autowired NoteDrafts drafts;

  @Test
  void aSeekerHearsTheyHaveANewTaskOncePerSession() throws Exception {
    String seeker =
        approvedSpecialist("+237696000513", "Task Seeker", staff("ops-t", "T", "OPERATIONS")).id();
    UUID booking = UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      drafts.onHomework(
          Events.envelope(
              "ai.homework.assigned",
              1,
              seeker,
              "en",
              java.util.Map.of(
                  "homeworkId", UUID.randomUUID().toString(), "bookingId", booking.toString())));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT text FROM notification WHERE kind = 'TASK_FROM_SPECIALIST' AND booking_id = ?",
                String.class,
                booking))
        .isEqualTo("You have a new task from your specialist.");
  }

  @Test
  void aSpecialistIsRemindedOnceWhenTheirLicenceEndsWithin60Days() throws Exception {
    Applicant soon =
        approvedSpecialist("+237696000511", "Licence Soon", staff("ops-l", "L", "OPERATIONS"));
    Applicant later =
        approvedSpecialist("+237696000512", "Licence Later", staff("ops-l2", "L", "OPERATIONS"));
    LocalDate today = LocalDate.now();
    jdbc.update(
        "UPDATE specialist_profession SET licence_valid_until = ? WHERE account_id = ?::uuid",
        today.plusDays(30),
        soon.id());
    jdbc.update(
        "UPDATE specialist_profession SET licence_valid_until = ? WHERE account_id = ?::uuid",
        today.plusDays(120),
        later.id());

    messages.licenceRenewalsNow(Instant.now());
    messages.licenceRenewalsNow(Instant.now());

    String count =
        "SELECT count(*) FROM notification WHERE kind = 'LICENCE_RENEWAL' AND channel = 'PUSH'"
            + " AND account_id = ?::uuid";
    assertThat(jdbc.queryForObject(count, Integer.class, soon.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject(count, Integer.class, later.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT text FROM notification WHERE kind = 'LICENCE_RENEWAL' AND account_id = ?::uuid"
                    + " AND channel = 'PUSH'",
                String.class,
                soon.id()))
        .startsWith("Your licence expires on ")
        .endsWith("Upload the renewal to stay bookable.");
  }
}
