package care.solace.core.safetyplan;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.identity.PersonalData;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The seeker's safety plan (SafetyPlan.dc.html; D57): warning signs, what calms them and people
 * they can reach, sealed at rest. The professional help step is fixed in the app (the on-call
 * specialist and the local emergency number), so it needs no storage. Only the seeker reads it.
 */
@Service
class SafetyPlans implements PersonalData {

  record Person(
      @NotBlank @Size(max = 40) String relation,
      @NotBlank @Size(max = 60) String name,
      @Size(max = 20) String phone) {}

  record Edit(
      @NotNull @Size(max = 500) String warningSigns,
      @NotNull @Size(max = 500) String calming,
      @NotNull @Size(max = 5) List<@Valid Person> people,
      UUID madeWithSpecialistId) {}

  record MadeWith(UUID id, String name) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Plan(
      String warningSigns,
      String calming,
      List<Person> people,
      MadeWith madeWith,
      Instant updatedAt) {}

  /** What is sealed: the seeker's words only. */
  private record Body(String warningSigns, String calming, List<Person> people) {}

  private final JdbcClient db;
  private final PlanSealer sealer;
  private final ObjectMapper json;
  private final BookingRecords bookings;
  private final SpecialistOffers specialists;
  private final Clock clock;

  SafetyPlans(
      JdbcClient db,
      PlanSealer sealer,
      ObjectMapper json,
      BookingRecords bookings,
      SpecialistOffers specialists,
      Clock clock) {
    this.db = db;
    this.sealer = sealer;
    this.json = json;
    this.bookings = bookings;
    this.specialists = specialists;
    this.clock = clock;
  }

  Optional<Plan> find(UUID accountId, Locale viewer) {
    return db.sql("SELECT sealed, made_with, updated_at FROM safety_plan WHERE account_id = :a")
        .param("a", accountId)
        .query(
            (rs, n) -> {
              Body body = read(sealer.open(rs.getBytes("sealed")));
              UUID with = rs.getObject("made_with", UUID.class);
              MadeWith madeWith =
                  with == null
                      ? null
                      : specialists
                          .summary(with, viewer)
                          .map(s -> new MadeWith(with, s.fullName()))
                          .orElse(null);
              return new Plan(
                  body.warningSigns(),
                  body.calming(),
                  body.people(),
                  madeWith,
                  rs.getTimestamp("updated_at").toInstant());
            })
        .optional();
  }

  Plan save(UUID accountId, Edit edit, Locale viewer) {
    UUID with = edit.madeWithSpecialistId();
    if (with != null
        && bookings.forSubject(accountId).stream().noneMatch(b -> b.specialistId().equals(with))) {
      // Only a specialist the seeker has booked can be named on their plan.
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    byte[] sealed =
        sealer.seal(
            write(new Body(edit.warningSigns().strip(), edit.calming().strip(), edit.people())));
    db.sql(
            """
            INSERT INTO safety_plan (account_id, sealed, made_with, updated_at)
            VALUES (:a, :s, :w, :now)
            ON CONFLICT (account_id) DO UPDATE SET sealed = :s, made_with = :w, updated_at = :now
            """)
        .param("a", accountId)
        .param("s", sealed)
        .param("w", with)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return find(accountId, viewer).orElseThrow();
  }

  void delete(UUID accountId) {
    db.sql("DELETE FROM safety_plan WHERE account_id = :a").param("a", accountId).update();
  }

  // --- Personal data ----------------------------------------------------------------------------

  @Override
  public String section() {
    return "safetyPlan";
  }

  @Override
  public Object export(UUID accountId) {
    return find(accountId, Locale.ENGLISH).orElse(null);
  }

  @Override
  public void erase(UUID accountId) {
    delete(accountId);
  }

  private byte[] write(Body body) {
    try {
      return json.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Body read(byte[] bytes) {
    try {
      return json.readValue(bytes, Body.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
