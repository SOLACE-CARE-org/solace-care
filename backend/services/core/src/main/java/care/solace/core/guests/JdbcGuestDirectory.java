package care.solace.core.guests;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link GuestDirectory} over {@code guest_contact}. */
@Component
class JdbcGuestDirectory implements GuestDirectory {

  private static final String COLUMNS =
      "id, phone, preferred_locale, created_at, claimed_by_account_id";

  private final JdbcClient db;

  JdbcGuestDirectory(JdbcClient db) {
    this.db = db;
  }

  @Override
  public Optional<Guest> byId(UUID id) {
    return db.sql("SELECT " + COLUMNS + " FROM guest_contact WHERE id = :id")
        .param("id", id)
        .query(JdbcGuestDirectory::row)
        .optional();
  }

  @Override
  public Optional<Guest> byPhone(String e164) {
    return db.sql("SELECT " + COLUMNS + " FROM guest_contact WHERE phone = :p")
        .param("p", e164)
        .query(JdbcGuestDirectory::row)
        .optional();
  }

  @Override
  public List<Guest> newest(int limit) {
    return db.sql(
            "SELECT "
                + COLUMNS
                + " FROM guest_contact WHERE claimed_by_account_id IS NULL"
                + " ORDER BY created_at DESC LIMIT :n")
        .param("n", limit)
        .query(JdbcGuestDirectory::row)
        .list();
  }

  @Override
  @Transactional
  public void forget(UUID id, Instant now) {
    db.sql("DELETE FROM guest_token WHERE guest_contact_id = :id").param("id", id).update();
    db.sql("UPDATE guest_contact SET phone = NULL, purged_at = :now WHERE id = :id")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Guest row(ResultSet rs, int n) throws SQLException {
    return new Guest(
        rs.getObject("id", UUID.class),
        rs.getString("phone"),
        Locale.forLanguageTag(rs.getString("preferred_locale")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("claimed_by_account_id", UUID.class));
  }
}
