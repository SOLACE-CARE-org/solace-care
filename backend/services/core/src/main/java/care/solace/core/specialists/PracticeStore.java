package care.solace.core.specialists;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A specialist's practice (D62): {@code specialist_profession}, {@code specialist_specialty} and
 * {@code specialty_request}. Each profession has its own licence; each specialty sits under one of
 * the specialist's professions.
 */
@Repository
class PracticeStore {

  private final JdbcClient db;

  PracticeStore(JdbcClient db) {
    this.db = db;
  }

  enum Status {
    PENDING,
    APPROVED,
    REJECTED,
    DECLINED,
    WITHDRAWN
  }

  record Profession(
      String code,
      int position,
      String licenceNumber,
      String licensingBody,
      Status status,
      boolean addedAfterApproval,
      LocalDate validUntil,
      ReviewStore.Verification verification,
      ReviewStore.Verification decision,
      String reviewNote,
      Instant requestedAt) {

    boolean live() {
      return status != Status.WITHDRAWN && status != Status.REJECTED;
    }
  }

  record Specialty(
      String profession,
      String code,
      Status status,
      String note,
      UUID certificateId,
      ReviewStore.Verification verification,
      ReviewStore.Verification decision,
      String reviewNote,
      Instant requestedAt) {

    boolean live() {
      return status != Status.WITHDRAWN && status != Status.DECLINED;
    }
  }

  record Request(
      UUID id,
      UUID accountId,
      String profession,
      String proposedName,
      String note,
      UUID certificateId,
      String status,
      String specialty,
      ReviewStore.Verification decision,
      String reviewNote,
      Instant requestedAt) {}

  /** What the Practice step saves for one profession while the application is a draft. */
  record Draft(
      String profession, String licenceNumber, String licensingBody, List<String> specialties) {}

  /** Something an approved specialist added that waits for staff. */
  record Addition(
      UUID specialistId,
      String fullName,
      String kind,
      String profession,
      String specialty,
      ReviewStore.Verification verification,
      Instant requestedAt) {}

  // --- Reading ---------------------------------------------------------------------------------

  /** Main profession first; withdrawn and rejected ones after the others. */
  List<Profession> professions(UUID accountId) {
    return db.sql(
            """
            SELECT profession_code, position, licence_number, licensing_body, status,
                   added_after_approval, licence_valid_until, verified_by_id, verified_by_name,
                   verified_at, decided_by_id, decided_by_name, decided_at, review_note, requested_at
              FROM specialist_profession WHERE account_id = :id
             ORDER BY status IN ('WITHDRAWN', 'REJECTED'), position
            """)
        .param("id", accountId)
        .query(PracticeStore::profession)
        .list();
  }

  /** In catalogue order within each profession. */
  List<Specialty> specialties(UUID accountId) {
    return db.sql(
            """
            SELECT ss.profession_code, ss.specialty_code, ss.status, ss.note, ss.certificate_id,
                   ss.verified_by_id, ss.verified_by_name, ss.verified_at, ss.decided_by_id,
                   ss.decided_by_name, ss.decided_at, ss.review_note, ss.requested_at
              FROM specialist_specialty ss JOIN specialty s ON s.code = ss.specialty_code
             WHERE ss.account_id = :id
             ORDER BY s.position
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new Specialty(
                    rs.getString("profession_code"),
                    rs.getString("specialty_code"),
                    Status.valueOf(rs.getString("status")),
                    rs.getString("note"),
                    rs.getObject("certificate_id", UUID.class),
                    verification(rs, "verified"),
                    verification(rs, "decided"),
                    rs.getString("review_note"),
                    rs.getTimestamp("requested_at").toInstant()))
        .list();
  }

  /** The main profession: the first approved one, else the first one still claimed. */
  Optional<String> mainProfession(UUID accountId) {
    return db.sql(
            """
            SELECT profession_code FROM specialist_profession
             WHERE account_id = :id AND status IN ('APPROVED', 'PENDING')
             ORDER BY status <> 'APPROVED', position LIMIT 1
            """)
        .param("id", accountId)
        .query(String.class)
        .optional();
  }

  /** A specialist's requests, newest first. */
  List<Request> requests(UUID accountId) {
    return db.sql(REQUEST_SELECT + " WHERE r.account_id = :id ORDER BY r.requested_at DESC")
        .param("id", accountId)
        .query(PracticeStore::request)
        .list();
  }

  Optional<Request> request(UUID id) {
    return db.sql(REQUEST_SELECT + " WHERE r.id = :id")
        .param("id", id)
        .query(PracticeStore::request)
        .optional();
  }

  /** Open requests oldest first, as staff work; decided ones newest first. */
  List<Request> requestsByStatus(String status) {
    String order = status.equals("OPEN") ? "r.requested_at ASC" : "r.decided_at DESC NULLS LAST";
    return db.sql(REQUEST_SELECT + " WHERE r.status = :s ORDER BY " + order + " LIMIT 200")
        .param("s", status)
        .query(PracticeStore::request)
        .list();
  }

  int openRequests(UUID accountId) {
    return db.sql(
            "SELECT count(*) FROM specialty_request WHERE account_id = :id AND status = 'OPEN'")
        .param("id", accountId)
        .query(Integer.class)
        .single();
  }

  /** What approved specialists added and staff have not decided, oldest first. */
  List<Addition> additions() {
    return db.sql(
            """
            SELECT p.account_id, p.full_name, 'PROFESSION' AS kind, sp.profession_code,
                   NULL AS specialty_code, sp.verified_by_id, sp.verified_by_name, sp.verified_at,
                   sp.requested_at
              FROM specialist_profession sp JOIN specialist_profile p ON p.account_id = sp.account_id
             WHERE sp.status = 'PENDING' AND p.verification_status = 'APPROVED'
            UNION ALL
            SELECT p.account_id, p.full_name, 'SPECIALTY', ss.profession_code, ss.specialty_code,
                   NULL, NULL, NULL, ss.requested_at
              FROM specialist_specialty ss
              JOIN specialist_profession sp
                ON sp.account_id = ss.account_id AND sp.profession_code = ss.profession_code
              JOIN specialist_profile p ON p.account_id = ss.account_id
             WHERE ss.status = 'PENDING' AND sp.status = 'APPROVED'
               AND p.verification_status = 'APPROVED'
             ORDER BY requested_at
             LIMIT 200
            """)
        .query(
            (rs, n) ->
                new Addition(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getString("kind"),
                    rs.getString("profession_code"),
                    rs.getString("specialty_code"),
                    verification(rs, "verified"),
                    rs.getTimestamp("requested_at").toInstant()))
        .list();
  }

  // --- The specialist's changes ----------------------------------------------------------------

  /**
   * Replaces a draft's professions and specialties: rows no longer listed go, kept ones are
   * updated, new ones are added, and everything waits for review again.
   */
  void replaceDraft(UUID accountId, List<Draft> drafts, Instant now) {
    List<String> codes = drafts.stream().map(Draft::profession).toList();
    db.sql(
            "DELETE FROM specialist_profession WHERE account_id = :id"
                + " AND profession_code NOT IN (:codes)")
        .param("id", accountId)
        .param("codes", codes)
        .update();
    for (int i = 0; i < drafts.size(); i++) {
      Draft d = drafts.get(i);
      db.sql(
              """
              INSERT INTO specialist_profession
                     (account_id, profession_code, position, licence_number, licensing_body, status,
                      requested_at)
              VALUES (:id, :p, :pos, :l, :b, 'PENDING', :now)
              ON CONFLICT (account_id, profession_code) DO UPDATE
                 SET position = EXCLUDED.position, licence_number = EXCLUDED.licence_number,
                     licensing_body = EXCLUDED.licensing_body, status = 'PENDING'
              """)
          .param("id", accountId)
          .param("p", d.profession())
          .param("pos", i + 1)
          .param("l", d.licenceNumber())
          .param("b", d.licensingBody())
          .param("now", Timestamp.from(now))
          .update();
      db.sql(
              "DELETE FROM specialist_specialty WHERE account_id = :id AND profession_code = :p"
                  + " AND specialty_code NOT IN (:s)")
          .param("id", accountId)
          .param("p", d.profession())
          .param("s", d.specialties())
          .update();
      for (String specialty : d.specialties()) {
        db.sql(
                """
                INSERT INTO specialist_specialty
                       (account_id, profession_code, specialty_code, status, requested_at)
                VALUES (:id, :p, :s, 'PENDING', :now)
                ON CONFLICT (account_id, profession_code, specialty_code) DO UPDATE
                   SET status = 'PENDING'
                """)
            .param("id", accountId)
            .param("p", d.profession())
            .param("s", specialty)
            .param("now", Timestamp.from(now))
            .update();
      }
    }
  }

  /**
   * A profession an approved specialist adds, or adds again after a withdrawal or rejection: it
   * waits as PENDING, after the professions they already have.
   */
  void addProfession(
      UUID accountId,
      String profession,
      String licenceNumber,
      String licensingBody,
      List<String> specialties,
      Instant now) {
    db.sql(
            """
            INSERT INTO specialist_profession
                   (account_id, profession_code, position, licence_number, licensing_body, status,
                    added_after_approval, requested_at)
            VALUES (:id, :p,
                    (SELECT coalesce(max(position), 0) + 1 FROM specialist_profession
                      WHERE account_id = :id),
                    :l, :b, 'PENDING', TRUE, :now)
            ON CONFLICT (account_id, profession_code) DO UPDATE
               SET licence_number = EXCLUDED.licence_number,
                   licensing_body = EXCLUDED.licensing_body, status = 'PENDING',
                   added_after_approval = TRUE, licence_valid_until = NULL,
                   verified_by_id = NULL, verified_by_name = NULL, verified_at = NULL,
                   decided_by_id = NULL, decided_by_name = NULL, decided_at = NULL,
                   review_note = NULL, requested_at = EXCLUDED.requested_at
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("l", licenceNumber)
        .param("b", licensingBody)
        .param("now", Timestamp.from(now))
        .update();
    for (String specialty : specialties) {
      addSpecialty(accountId, profession, specialty, null, null, now);
    }
  }

  /** A specialty to check, new or added again; a request that became one carries its evidence. */
  void addSpecialty(
      UUID accountId,
      String profession,
      String specialty,
      String note,
      UUID certificateId,
      Instant now) {
    db.sql(
            """
            INSERT INTO specialist_specialty
                   (account_id, profession_code, specialty_code, status, note, certificate_id,
                    requested_at)
            VALUES (:id, :p, :s, 'PENDING', :note, :cert, :now)
            ON CONFLICT (account_id, profession_code, specialty_code) DO UPDATE
               SET status = 'PENDING', note = EXCLUDED.note,
                   certificate_id = EXCLUDED.certificate_id,
                   verified_by_id = NULL, verified_by_name = NULL, verified_at = NULL,
                   decided_by_id = NULL, decided_by_name = NULL, decided_at = NULL,
                   review_note = NULL, requested_at = EXCLUDED.requested_at
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("s", specialty)
        .param("note", note)
        .param("cert", certificateId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Withdraws a profession and everything under it that is still claimed. */
  void withdrawProfession(UUID accountId, String profession, Instant now) {
    db.sql(
            """
            UPDATE specialist_specialty SET status = 'WITHDRAWN', decided_at = :now,
                   decided_by_id = NULL, decided_by_name = NULL
             WHERE account_id = :id AND profession_code = :p AND status IN ('PENDING', 'APPROVED')
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("now", Timestamp.from(now))
        .update();
    db.sql(
            """
            UPDATE specialty_request SET status = 'WITHDRAWN', decided_at = :now
             WHERE account_id = :id AND profession_code = :p AND status = 'OPEN'
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("now", Timestamp.from(now))
        .update();
    db.sql(
            """
            UPDATE specialist_profession SET status = 'WITHDRAWN', decided_at = :now,
                   decided_by_id = NULL, decided_by_name = NULL
             WHERE account_id = :id AND profession_code = :p
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("now", Timestamp.from(now))
        .update();
  }

  void withdrawSpecialty(UUID accountId, String profession, String specialty, Instant now) {
    db.sql(
            """
            UPDATE specialist_specialty SET status = 'WITHDRAWN', decided_at = :now,
                   decided_by_id = NULL, decided_by_name = NULL
             WHERE account_id = :id AND profession_code = :p AND specialty_code = :s
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("s", specialty)
        .param("now", Timestamp.from(now))
        .update();
  }

  void insertRequest(Request r) {
    db.sql(
            """
            INSERT INTO specialty_request
                   (id, account_id, profession_code, proposed_name, note, certificate_id, status,
                    requested_at)
            VALUES (:id, :a, :p, :name, :note, :cert, 'OPEN', :now)
            """)
        .param("id", r.id())
        .param("a", r.accountId())
        .param("p", r.profession())
        .param("name", r.proposedName())
        .param("note", r.note())
        .param("cert", r.certificateId())
        .param("now", Timestamp.from(r.requestedAt()))
        .update();
  }

  /** Moves an open request on; true only for the call that did it. */
  boolean decideRequest(
      UUID id,
      String status,
      String specialty,
      ReviewStore.StaffRef staff,
      String reason,
      Instant now) {
    return db.sql(
                """
                UPDATE specialty_request
                   SET status = :st, specialty_code = :sp, decided_by_id = :sid,
                       decided_by_name = :sname, decided_at = :now, review_note = :r
                 WHERE id = :id AND status = 'OPEN'
                """)
            .param("id", id)
            .param("st", status)
            .param("sp", specialty)
            .param("sid", staff == null ? null : staff.id())
            .param("sname", staff == null ? null : staff.name())
            .param("now", Timestamp.from(now))
            .param("r", reason)
            .update()
        == 1;
  }

  // --- Staff checks ----------------------------------------------------------------------------

  void verifyProfession(
      UUID accountId,
      String profession,
      ReviewStore.StaffRef staff,
      LocalDate validUntil,
      Instant now) {
    db.sql(
            """
            UPDATE specialist_profession
               SET verified_by_id = :sid, verified_by_name = :sname, verified_at = :now,
                   licence_valid_until = :d
             WHERE account_id = :id AND profession_code = :p
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .param("d", validUntil)
        .update();
  }

  void unverifyProfession(UUID accountId, String profession) {
    db.sql(
            """
            UPDATE specialist_profession
               SET verified_by_id = NULL, verified_by_name = NULL, verified_at = NULL
             WHERE account_id = :id AND profession_code = :p
            """)
        .param("id", accountId)
        .param("p", profession)
        .update();
  }

  void verifySpecialty(
      UUID accountId,
      String profession,
      String specialty,
      ReviewStore.StaffRef staff,
      Instant now) {
    db.sql(
            """
            UPDATE specialist_specialty
               SET verified_by_id = :sid, verified_by_name = :sname, verified_at = :now
             WHERE account_id = :id AND profession_code = :p AND specialty_code = :s
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("s", specialty)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  void unverifySpecialty(UUID accountId, String profession, String specialty) {
    db.sql(
            """
            UPDATE specialist_specialty
               SET verified_by_id = NULL, verified_by_name = NULL, verified_at = NULL
             WHERE account_id = :id AND profession_code = :p AND specialty_code = :s
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("s", specialty)
        .update();
  }

  /** A pending profession is approved or rejected; true only for the call that did it. */
  boolean decideProfession(
      UUID accountId,
      String profession,
      Status to,
      ReviewStore.StaffRef staff,
      String reason,
      Instant now) {
    return db.sql(
                """
                UPDATE specialist_profession
                   SET status = :to, decided_by_id = :sid, decided_by_name = :sname,
                       decided_at = :now, review_note = :r
                 WHERE account_id = :id AND profession_code = :p AND status = 'PENDING'
                """)
            .param("id", accountId)
            .param("p", profession)
            .param("to", to.name())
            .param("sid", staff.id())
            .param("sname", staff.name())
            .param("now", Timestamp.from(now))
            .param("r", reason)
            .update()
        == 1;
  }

  /** A pending specialty is approved or declined; true only for the call that did it. */
  boolean decideSpecialty(
      UUID accountId,
      String profession,
      String specialty,
      Status to,
      ReviewStore.StaffRef staff,
      String reason,
      Instant now) {
    return db.sql(
                """
                UPDATE specialist_specialty
                   SET status = :to, decided_by_id = :sid, decided_by_name = :sname,
                       decided_at = :now, review_note = :r
                 WHERE account_id = :id AND profession_code = :p AND specialty_code = :s
                   AND status = 'PENDING'
                """)
            .param("id", accountId)
            .param("p", profession)
            .param("s", specialty)
            .param("to", to.name())
            .param("sid", staff.id())
            .param("sname", staff.name())
            .param("now", Timestamp.from(now))
            .param("r", reason)
            .update()
        == 1;
  }

  /** The pending specialties of one profession, declined with it. */
  void declinePendingSpecialties(
      UUID accountId, String profession, ReviewStore.StaffRef staff, String reason, Instant now) {
    db.sql(
            """
            UPDATE specialist_specialty
               SET status = 'DECLINED', decided_by_id = :sid, decided_by_name = :sname,
                   decided_at = :now, review_note = :r
             WHERE account_id = :id AND profession_code = :p AND status = 'PENDING'
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .param("r", reason)
        .update();
  }

  /** The checked specialties of one profession, approved with it. */
  void approveVerifiedSpecialties(
      UUID accountId, String profession, ReviewStore.StaffRef staff, Instant now) {
    db.sql(
            """
            UPDATE specialist_specialty
               SET status = 'APPROVED', decided_by_id = :sid, decided_by_name = :sname,
                   decided_at = :now
             WHERE account_id = :id AND profession_code = :p AND status = 'PENDING'
               AND verified_at IS NOT NULL
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** On an application's approval (D29): every checked profession and specialty with it. */
  void approveApplication(UUID accountId, ReviewStore.StaffRef staff, Instant now) {
    db.sql(
            """
            UPDATE specialist_profession
               SET status = 'APPROVED', decided_by_id = :sid, decided_by_name = :sname,
                   decided_at = :now
             WHERE account_id = :id AND status = 'PENDING' AND verified_at IS NOT NULL
            """)
        .param("id", accountId)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .update();
    db.sql(
            """
            UPDATE specialist_specialty ss
               SET status = 'APPROVED', decided_by_id = :sid, decided_by_name = :sname,
                   decided_at = :now
              FROM specialist_profession sp
             WHERE sp.account_id = ss.account_id AND sp.profession_code = ss.profession_code
               AND sp.status = 'APPROVED' AND ss.account_id = :id AND ss.status = 'PENDING'
               AND ss.verified_at IS NOT NULL
            """)
        .param("id", accountId)
        .param("sid", staff.id())
        .param("sname", staff.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** On resubmission after a change request: everything still claimed is checked again. */
  void restartReview(UUID accountId) {
    db.sql(
            """
            UPDATE specialist_profession
               SET status = 'PENDING', verified_by_id = NULL, verified_by_name = NULL,
                   verified_at = NULL, decided_by_id = NULL, decided_by_name = NULL,
                   decided_at = NULL, review_note = NULL
             WHERE account_id = :id AND status <> 'WITHDRAWN'
            """)
        .param("id", accountId)
        .update();
    db.sql(
            """
            UPDATE specialist_specialty
               SET status = 'PENDING', verified_by_id = NULL, verified_by_name = NULL,
                   verified_at = NULL, decided_by_id = NULL, decided_by_name = NULL,
                   decided_at = NULL, review_note = NULL
             WHERE account_id = :id AND status <> 'WITHDRAWN'
            """)
        .param("id", accountId)
        .update();
  }

  // --- Mapping ---------------------------------------------------------------------------------

  private static final String REQUEST_SELECT =
      """
      SELECT r.id, r.account_id, r.profession_code, r.proposed_name, r.note, r.certificate_id,
             r.status, r.specialty_code, r.decided_by_id, r.decided_by_name, r.decided_at,
             r.review_note, r.requested_at
        FROM specialty_request r
      """;

  private static Profession profession(ResultSet rs, int n) throws SQLException {
    return new Profession(
        rs.getString("profession_code"),
        rs.getInt("position"),
        rs.getString("licence_number"),
        rs.getString("licensing_body"),
        Status.valueOf(rs.getString("status")),
        rs.getBoolean("added_after_approval"),
        rs.getObject("licence_valid_until", LocalDate.class),
        verification(rs, "verified"),
        verification(rs, "decided"),
        rs.getString("review_note"),
        rs.getTimestamp("requested_at").toInstant());
  }

  private static Request request(ResultSet rs, int n) throws SQLException {
    return new Request(
        rs.getObject("id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getString("profession_code"),
        rs.getString("proposed_name"),
        rs.getString("note"),
        rs.getObject("certificate_id", UUID.class),
        rs.getString("status"),
        rs.getString("specialty_code"),
        verification(rs, "decided"),
        rs.getString("review_note"),
        rs.getTimestamp("requested_at").toInstant());
  }

  /** {@code <prefix>_by_id}, {@code <prefix>_by_name} and {@code <prefix>_at}, or null. */
  private static ReviewStore.Verification verification(ResultSet rs, String prefix)
      throws SQLException {
    Timestamp at = rs.getTimestamp(prefix + "_at");
    if (at == null) {
      return null;
    }
    String id = rs.getString(prefix + "_by_id");
    return new ReviewStore.Verification(
        id == null ? null : new ReviewStore.StaffRef(id, rs.getString(prefix + "_by_name")),
        at.toInstant());
  }
}
