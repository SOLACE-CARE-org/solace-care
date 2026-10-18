package care.solace.core.specialists;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The staff side of an application: assignment, verified items and language levels, notes, the
 * activity log, and the status changes reviewers make.
 */
@Repository
class ReviewStore {

  private final JdbcClient db;

  ReviewStore(JdbcClient db) {
    this.db = db;
  }

  enum Tab {
    SUBMITTED,
    UNDER_REVIEW,
    CHANGES_REQUESTED,
    DECIDED
  }

  /** Items a reviewer ticks; languages and services are not among them. */
  enum Item {
    IDENTITY,
    LICENCE,
    PROFILE,
    PAYOUT
  }

  record StaffRef(String id, String name) {}

  record Verification(StaffRef by, Instant at) {}

  record QueueRow(
      UUID id,
      String fullName,
      String profession,
      ApplicationStatus status,
      Instant submittedAt,
      StaffRef assignedTo) {}

  record State(
      StaffRef assignedTo,
      Instant reviewStartedAt,
      Instant changesRequestedAt,
      Instant decidedAt,
      LocalDate licenceValidUntil,
      String staffNotes,
      Verification approvalProposal,
      String bioDraftedLanguage) {}

  record LanguageReview(
      String code, String declared, String verifiedLevel, Verification verification) {}

  record Activity(Instant at, String kind, String subject, String value, StaffRef by) {}

  // --- Reading ---------------------------------------------------------------------------------

  List<QueueRow> queue(Tab tab, String profession, String language) {
    String where =
        switch (tab) {
          case SUBMITTED -> "p.verification_status = 'SUBMITTED'";
          case UNDER_REVIEW -> "p.verification_status = 'UNDER_REVIEW'";
          case CHANGES_REQUESTED ->
              "p.verification_status = 'DRAFT' AND p.changes_requested_at IS NOT NULL";
          case DECIDED -> "p.verification_status IN ('APPROVED', 'REJECTED', 'SUSPENDED')";
        };
    // Oldest first, as reviewers work; decided ones newest first.
    String order =
        tab == Tab.DECIDED ? "p.decided_at DESC NULLS LAST" : "p.submitted_at ASC NULLS LAST";
    return db.sql(
            """
            SELECT p.account_id, p.full_name, p.profession_code, p.verification_status,
                   p.submitted_at, p.assigned_staff_id, p.assigned_staff_name
              FROM specialist_profile p
             WHERE %s
               AND (CAST(:profession AS TEXT) IS NULL OR p.profession_code = :profession)
               AND (CAST(:language AS TEXT) IS NULL OR EXISTS (
                     SELECT 1 FROM specialist_language l
                      WHERE l.account_id = p.account_id AND l.language_code = :language))
             ORDER BY %s, p.account_id
             LIMIT 200
            """
                .formatted(where, order))
        .param("profession", profession)
        .param("language", language)
        .query(
            (rs, n) ->
                new QueueRow(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getString("profession_code"),
                    ApplicationStatus.valueOf(rs.getString("verification_status")),
                    instant(rs, "submitted_at"),
                    staff(rs, "assigned_staff_id", "assigned_staff_name")))
        .list();
  }

  Map<Tab, Integer> counts() {
    Map<Tab, Integer> counts = new EnumMap<>(Tab.class);
    db.sql(
            """
            SELECT count(*) FILTER (WHERE verification_status = 'SUBMITTED') AS submitted,
                   count(*) FILTER (WHERE verification_status = 'UNDER_REVIEW') AS under_review,
                   count(*) FILTER (WHERE verification_status = 'DRAFT'
                                      AND changes_requested_at IS NOT NULL) AS changes_requested
              FROM specialist_profile
            """)
        .query(
            rs -> {
              counts.put(Tab.SUBMITTED, rs.getInt("submitted"));
              counts.put(Tab.UNDER_REVIEW, rs.getInt("under_review"));
              counts.put(Tab.CHANGES_REQUESTED, rs.getInt("changes_requested"));
            });
    return counts;
  }

  State state(UUID id) {
    return db.sql(
            """
            SELECT assigned_staff_id, assigned_staff_name, review_started_at, changes_requested_at,
                   decided_at, licence_valid_until, staff_notes, approval_proposed_by_id,
                   approval_proposed_by_name, approval_proposed_at, bio_drafted_language
              FROM specialist_profile WHERE account_id = :id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new State(
                    staff(rs, "assigned_staff_id", "assigned_staff_name"),
                    instant(rs, "review_started_at"),
                    instant(rs, "changes_requested_at"),
                    instant(rs, "decided_at"),
                    rs.getObject("licence_valid_until", LocalDate.class),
                    rs.getString("staff_notes"),
                    proposal(rs),
                    rs.getString("bio_drafted_language")))
        .single();
  }

  Map<Item, Verification> items(UUID id) {
    Map<Item, Verification> items = new EnumMap<>(Item.class);
    db.sql(
            """
            SELECT item, verified_by_id, verified_by_name, verified_at
              FROM specialist_review_item WHERE account_id = :id
            """)
        .param("id", id)
        .query(
            rs -> {
              items.put(
                  Item.valueOf(rs.getString("item")),
                  new Verification(
                      new StaffRef(
                          rs.getString("verified_by_id"), rs.getString("verified_by_name")),
                      instant(rs, "verified_at")));
            });
    return items;
  }

  List<LanguageReview> languages(UUID id) {
    return db.sql(
            """
            SELECT l.language_code, l.proficiency, l.verified_level, l.verified_at,
                   l.verified_by_id, l.verified_by_name
              FROM specialist_language l JOIN session_language s ON s.code = l.language_code
             WHERE l.account_id = :id ORDER BY s.position
            """)
        .param("id", id)
        .query(
            (rs, n) -> {
              StaffRef by = staff(rs, "verified_by_id", "verified_by_name");
              Instant at = instant(rs, "verified_at");
              return new LanguageReview(
                  rs.getString("language_code"),
                  rs.getString("proficiency"),
                  rs.getString("verified_level"),
                  by == null || at == null ? null : new Verification(by, at));
            })
        .list();
  }

  List<Activity> activity(UUID id) {
    return db.sql(
            """
            SELECT at, kind, subject, value, staff_id, staff_name FROM specialist_review_activity
             WHERE account_id = :id ORDER BY at DESC, id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new Activity(
                    instant(rs, "at"),
                    rs.getString("kind"),
                    rs.getString("subject"),
                    rs.getString("value"),
                    staff(rs, "staff_id", "staff_name")))
        .list();
  }

  /** Approved or suspended specialists whose name or licence number contains {@code query}. */
  List<UUID> search(String query) {
    String like =
        "%"
            + query.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            + "%";
    return db.sql(
            """
            SELECT account_id FROM specialist_profile
             WHERE verification_status IN ('APPROVED', 'SUSPENDED')
               AND (lower(full_name) LIKE :q OR lower(licence_number) LIKE :q)
             ORDER BY full_name LIMIT 20
            """)
        .param("q", like)
        .query(UUID.class)
        .list();
  }

  // --- Changing --------------------------------------------------------------------------------

  /** SUBMITTED to UNDER_REVIEW, or a new assignee for one already under review. */
  boolean open(UUID id, StaffRef staff, Instant now) {
    return db.sql(
                """
                UPDATE specialist_profile
                   SET verification_status = 'UNDER_REVIEW', assigned_staff_id = :sid,
                       assigned_staff_name = :sname,
                       review_started_at = COALESCE(review_started_at, :now), updated_at = :now
                 WHERE account_id = :id AND verification_status IN ('SUBMITTED', 'UNDER_REVIEW')
                """)
            .param("id", id)
            .param("sid", staff.id())
            .param("sname", staff.name())
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void verify(UUID id, Item item, StaffRef staff, Instant now) {
    db.sql(
            """
            INSERT INTO specialist_review_item
                   (account_id, item, verified_by_id, verified_by_name, verified_at)
            VALUES (:id, :item, :sid, :sname, :now)
            ON CONFLICT (account_id, item) DO UPDATE
               SET verified_by_id = EXCLUDED.verified_by_id,
                   verified_by_name = EXCLUDED.verified_by_name,
                   verified_at = EXCLUDED.verified_at
            """)
        .param("id", id)
        .param("item", item.name())
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  void unverify(UUID id, Item item) {
    db.sql("DELETE FROM specialist_review_item WHERE account_id = :id AND item = :item")
        .param("id", id)
        .param("item", item.name())
        .update();
  }

  void licenceValidUntil(UUID id, LocalDate date) {
    db.sql("UPDATE specialist_profile SET licence_valid_until = :d WHERE account_id = :id")
        .param("id", id)
        .param("d", date)
        .update();
  }

  boolean verifyLanguage(UUID id, String code, String level, StaffRef staff, Instant now) {
    return db.sql(
                """
                UPDATE specialist_language
                   SET verified_level = :level, verified_at = :now, verified_by_id = :sid,
                       verified_by_name = :sname
                 WHERE account_id = :id AND language_code = :code
                """)
            .param("id", id)
            .param("code", code)
            .param("level", level)
            .param("sid", staff.id())
            .param("sname", staff.name())
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void bio(UUID id, String language, String text, Instant now) {
    String column = "fr".equals(language) ? "bio_fr" : "bio_en";
    db.sql(
            "UPDATE specialist_profile SET "
                + column
                + " = :t, bio_language = COALESCE(bio_language, :l),"
                // Staff writing the language the specialist did not: a Solace draft (D30).
                + " bio_drafted_language = CASE WHEN bio_language IS NOT NULL AND bio_language <> :l"
                + " THEN :l ELSE bio_drafted_language END,"
                + " bio_draft_confirmed_at = NULL, updated_at = :now"
                + " WHERE account_id = :id")
        .param("id", id)
        .param("t", text)
        .param("l", language)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Records the first person's approval (D29); true only if none was waiting. */
  boolean proposeApproval(UUID id, StaffRef staff, Instant now) {
    return db.sql(
                """
                UPDATE specialist_profile
                   SET approval_proposed_by_id = :sid, approval_proposed_by_name = :sname,
                       approval_proposed_at = :now
                 WHERE account_id = :id AND verification_status = 'UNDER_REVIEW'
                   AND approval_proposed_by_id IS NULL
                """)
            .param("id", id)
            .param("sid", staff.id())
            .param("sname", staff.name())
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  /** Withdraws a waiting approval; true only if there was one. */
  boolean clearProposal(UUID id) {
    return db.sql(
                """
                UPDATE specialist_profile
                   SET approval_proposed_by_id = NULL, approval_proposed_by_name = NULL,
                       approval_proposed_at = NULL
                 WHERE account_id = :id AND approval_proposed_by_id IS NOT NULL
                """)
            .param("id", id)
            .update()
        == 1;
  }

  void notes(UUID id, String notes) {
    db.sql("UPDATE specialist_profile SET staff_notes = :n WHERE account_id = :id")
        .param("id", id)
        .param("n", notes.isBlank() ? null : notes)
        .update();
  }

  void interview(UUID id, Instant at) {
    db.sql("UPDATE specialist_profile SET interview_at = :at WHERE account_id = :id")
        .param("id", id)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Moves the status from one of {@code from} to {@code to}; true only for the call that did it. A
   * reason goes to the applicant; a change request also marks the application as sent back.
   */
  boolean move(
      UUID id, List<ApplicationStatus> from, ApplicationStatus to, String reason, Instant now) {
    boolean decided = to == ApplicationStatus.APPROVED || to == ApplicationStatus.REJECTED;
    return db.sql(
                """
                UPDATE specialist_profile
                   SET verification_status = :to,
                       review_note = COALESCE(:reason, review_note),
                       changes_requested_at = CASE WHEN :changes THEN :now ELSE changes_requested_at END,
                       decided_at = CASE WHEN :decided THEN :now ELSE decided_at END,
                       updated_at = :now
                 WHERE account_id = :id AND verification_status IN (:from)
                """)
            .param("id", id)
            .param("to", to.name())
            .param("reason", reason)
            .param("changes", to == ApplicationStatus.DRAFT)
            .param("decided", decided)
            .param("now", Timestamp.from(now))
            .param("from", from.stream().map(Enum::name).toList())
            .update()
        == 1;
  }

  /**
   * On resubmission after a change request: the applicant may have changed anything, so every
   * verification starts again.
   */
  void restartReview(UUID id) {
    db.sql("DELETE FROM specialist_review_item WHERE account_id = :id").param("id", id).update();
    db.sql(
            """
            UPDATE specialist_language
               SET verified_level = NULL, verified_at = NULL, verified_by_id = NULL,
                   verified_by_name = NULL
             WHERE account_id = :id
            """)
        .param("id", id)
        .update();
    db.sql(
            """
            UPDATE specialist_profile
               SET changes_requested_at = NULL, assigned_staff_id = NULL, assigned_staff_name = NULL,
                   review_started_at = NULL, approval_proposed_by_id = NULL,
                   approval_proposed_by_name = NULL, approval_proposed_at = NULL
             WHERE account_id = :id
            """)
        .param("id", id)
        .update();
  }

  void record(UUID id, String kind, String subject, String value, StaffRef by, Instant now) {
    db.sql(
            """
            INSERT INTO specialist_review_activity
                   (id, account_id, at, kind, subject, value, staff_id, staff_name)
            VALUES (:aid, :id, :at, :kind, :subject, :value, :sid, :sname)
            """)
        .param("aid", UUID.randomUUID())
        .param("id", id)
        .param("at", Timestamp.from(now))
        .param("kind", kind)
        .param("subject", subject)
        .param("value", value)
        .param("sid", by == null ? null : by.id())
        .param("sname", by == null ? null : by.name())
        .update();
  }

  Optional<UUID> exists(UUID id) {
    return db.sql("SELECT account_id FROM specialist_profile WHERE account_id = :id")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  private static Verification proposal(ResultSet rs) throws SQLException {
    StaffRef by = staff(rs, "approval_proposed_by_id", "approval_proposed_by_name");
    Instant at = instant(rs, "approval_proposed_at");
    return by == null || at == null ? null : new Verification(by, at);
  }

  private static StaffRef staff(ResultSet rs, String idColumn, String nameColumn)
      throws SQLException {
    String id = rs.getString(idColumn);
    return id == null ? null : new StaffRef(id, rs.getString(nameColumn));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
