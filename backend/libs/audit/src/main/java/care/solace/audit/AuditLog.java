package care.solace.audit;

import care.solace.audit.AuditEntry.Actor;
import care.solace.audit.AuditEntry.Outcome;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Writes audit entries. Call it on every read of clinical data (tracking entries, notes, health
 * check answers, transcripts, crisis alerts) and every admin action, before returning the data or
 * confirming the change.
 *
 * <p>Each entry is written in its own transaction: it stays even if the caller's transaction rolls
 * back, and if it cannot be written the call fails, so no clinical data leaves without an entry.
 */
public interface AuditLog {

  void record(AuditEntry entry);

  /** The signed-in actor of the current request. */
  Actor currentActor();

  /** Records that the current actor read someone's clinical data. */
  default void read(String action, String targetType, String targetId, String subjectId) {
    record(
        new AuditEntry(
            currentActor(), action, targetType, targetId, subjectId, Outcome.ALLOWED, null, null));
  }

  /** Records that the current actor was refused access to someone's clinical data. */
  default void denied(String action, String targetType, String targetId, String subjectId) {
    record(
        new AuditEntry(
            currentActor(), action, targetType, targetId, subjectId, Outcome.DENIED, null, null));
  }

  /** Records an admin action, with the names (not values) of what changed. */
  default void adminAction(
      String action, String targetType, String targetId, Map<String, String> details) {
    record(
        new AuditEntry(
            currentActor(), action, targetType, targetId, null, Outcome.ALLOWED, null, details));
  }

  /**
   * Records an admin action with the reason the staff member gave (Admin Portal Spec: every change
   * of money, a status, a policy, a setting or a module needs a written reason).
   */
  default void adminAction(
      String action, String targetType, String targetId, String reason, Map<String, String> details) {
    record(
        new AuditEntry(
            currentActor(), action, targetType, targetId, null, Outcome.ALLOWED, reason, details));
  }

  /** Records a read, then runs it. Nothing is read if the entry cannot be written. */
  default <T> T readThen(
      String action, String targetType, String targetId, String subjectId, Supplier<T> reader) {
    read(action, targetType, targetId, subjectId);
    return reader.get();
  }
}
