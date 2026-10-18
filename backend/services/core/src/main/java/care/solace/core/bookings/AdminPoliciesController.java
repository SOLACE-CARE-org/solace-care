package care.solace.core.bookings;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cancellation policy versions (admin screen 14, Catalogue and policies; tag admin-catalogue; D33,
 * D43). Each booking keeps the version in force when it was made, so a new version only changes
 * bookings made after it.
 */
@RestController
class AdminPoliciesController {

  private final StaffAccess access;
  private final JdbcClient db;
  private final AuditLog audit;
  private final Clock clock;

  AdminPoliciesController(StaffAccess access, JdbcClient db, AuditLog audit, Clock clock) {
    this.access = access;
    this.db = db;
    this.audit = audit;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PolicyView(
      int version,
      int fullRefundHours,
      int partialRefundHours,
      int partialRefundPercent,
      int seekerNoShowRefundPercent,
      int specialistNoShowRefundPercent,
      int maxReschedules,
      int rescheduleMinHours,
      Instant publishedAt,
      String publishedBy,
      String reason,
      boolean inForce) {}

  record NewPolicy(
      @Min(0) @Max(336) int fullRefundHours,
      @Min(0) @Max(336) int partialRefundHours,
      @Min(0) @Max(100) int partialRefundPercent,
      @Min(0) @Max(100) int seekerNoShowRefundPercent,
      @Min(0) @Max(100) int specialistNoShowRefundPercent,
      @Min(0) @Max(10) int maxReschedules,
      @Min(0) @Max(336) int rescheduleMinHours,
      @NotBlank @Size(max = 500) String reason) {}

  @GetMapping("/admin/v1/cancellation-policies")
  List<PolicyView> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, "catalogue", Level.VIEW);
    return versions();
  }

  @PostMapping("/admin/v1/cancellation-policies")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  PolicyView publish(@AuthenticationPrincipal Jwt token, @Valid @RequestBody NewPolicy p) {
    var staff = access.require(token, "catalogue", Level.CHANGE);
    // A later cancellation cannot get more back than an earlier one.
    if (p.partialRefundHours() > p.fullRefundHours()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    int version =
        db.sql("SELECT coalesce(max(version), 0) + 1 FROM cancellation_policy")
            .query(Integer.class)
            .single();
    db.sql(
            """
            INSERT INTO cancellation_policy (version, full_refund_hours, partial_refund_hours,
                   partial_refund_percent, seeker_no_show_refund_percent,
                   specialist_no_show_refund_percent, max_reschedules, reschedule_min_hours,
                   published_at, published_by, reason)
            VALUES (:v, :full, :partial, :pp, :seeker, :specialist, :max, :min, :at, :by, :reason)
            """)
        .param("v", version)
        .param("full", p.fullRefundHours())
        .param("partial", p.partialRefundHours())
        .param("pp", p.partialRefundPercent())
        .param("seeker", p.seekerNoShowRefundPercent())
        .param("specialist", p.specialistNoShowRefundPercent())
        .param("max", p.maxReschedules())
        .param("min", p.rescheduleMinHours())
        .param("at", Timestamp.from(clock.instant()))
        .param("by", staff.name())
        .param("reason", p.reason().trim())
        .update();
    audit.adminAction(
        "catalogue.policy.publish",
        "cancellation-policy",
        String.valueOf(version),
        p.reason().trim(),
        Map.of());
    return versions().get(0);
  }

  private List<PolicyView> versions() {
    var rows =
        db.sql("SELECT * FROM cancellation_policy ORDER BY version DESC")
            .query(
                (rs, n) ->
                    new PolicyView(
                        rs.getInt("version"),
                        rs.getInt("full_refund_hours"),
                        rs.getInt("partial_refund_hours"),
                        rs.getInt("partial_refund_percent"),
                        rs.getInt("seeker_no_show_refund_percent"),
                        rs.getInt("specialist_no_show_refund_percent"),
                        rs.getInt("max_reschedules"),
                        rs.getInt("reschedule_min_hours"),
                        rs.getTimestamp("published_at").toInstant(),
                        rs.getString("published_by"),
                        rs.getString("reason"),
                        n == 0))
            .list();
    return rows;
  }
}
