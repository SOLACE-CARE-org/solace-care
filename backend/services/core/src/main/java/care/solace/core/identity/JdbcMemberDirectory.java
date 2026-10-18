package care.solace.core.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link MemberDirectory} over {@code user_account}. */
@Component
class JdbcMemberDirectory implements MemberDirectory {

  private static final String COLUMNS =
      "id, type, status, preferred_locale, phone, email, created_at";

  private final JdbcClient db;

  JdbcMemberDirectory(JdbcClient db) {
    this.db = db;
  }

  @Override
  public Optional<Member> byId(UUID id) {
    return db.sql("SELECT " + COLUMNS + " FROM user_account WHERE id = :id")
        .param("id", id)
        .query(JdbcMemberDirectory::row)
        .optional();
  }

  @Override
  public Optional<Member> byPhone(String e164) {
    return db.sql("SELECT " + COLUMNS + " FROM user_account WHERE phone = :p")
        .param("p", e164)
        .query(JdbcMemberDirectory::row)
        .optional();
  }

  @Override
  public Optional<Member> byEmail(String email) {
    return db.sql("SELECT " + COLUMNS + " FROM user_account WHERE email = :e")
        .param("e", email.trim().toLowerCase(Locale.ROOT))
        .query(JdbcMemberDirectory::row)
        .optional();
  }

  @Override
  public List<Member> newest(int limit) {
    return db.sql("SELECT " + COLUMNS + " FROM user_account ORDER BY created_at DESC LIMIT :n")
        .param("n", limit)
        .query(JdbcMemberDirectory::row)
        .list();
  }

  private static Member row(ResultSet rs, int n) throws SQLException {
    return new Member(
        rs.getObject("id", UUID.class),
        Account.Type.valueOf(rs.getString("type")),
        Account.Status.valueOf(rs.getString("status")),
        Locale.forLanguageTag(rs.getString("preferred_locale")),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getTimestamp("created_at").toInstant());
  }
}
