package care.solace.core.bookings;

import care.solace.contracts.core.v1.BookingAccessServiceGrpc;
import care.solace.contracts.core.v1.CheckLiveBookingRequest;
import care.solace.contracts.core.v1.CheckLiveBookingResponse;
import care.solace.core.admin.PlatformSettings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Tracking asks this on every specialist read of a seeker's tracking data, never cached (Tracking
 * Service Spec, Specialist reads; D52). The relationship is live while a confirmed or in-progress
 * booking exists between the two, and for [7] days after their last completed session.
 */
@Service
class BookingAccessEndpoint extends BookingAccessServiceGrpc.BookingAccessServiceImplBase {

  static final Duration AFTER_SESSION = Duration.ofDays(7);

  private final JdbcClient db;
  private final PlatformSettings settings;
  private final Clock clock;

  BookingAccessEndpoint(JdbcClient db, PlatformSettings settings, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public void checkLiveBooking(
      CheckLiveBookingRequest request, StreamObserver<CheckLiveBookingResponse> out) {
    UUID seeker;
    UUID specialist;
    try {
      seeker = UUID.fromString(request.getSeekerSubjectId());
      specialist = UUID.fromString(request.getSpecialistSubjectId());
    } catch (IllegalArgumentException e) {
      out.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    Duration after = settings.duration("solace.tracking.sharing-after-session", AFTER_SESSION);
    var booking =
        db.sql(
                """
                SELECT id FROM booking
                 WHERE seeker_account_id = :seeker AND specialist_id = :sp
                   AND (status IN ('CONFIRMED', 'IN_PROGRESS')
                        OR (status = 'COMPLETED' AND ends_at > :since))
                 ORDER BY starts_at DESC LIMIT 1
                """)
            .param("seeker", seeker)
            .param("sp", specialist)
            .param("since", Timestamp.from(clock.instant().minus(after)))
            .query(UUID.class)
            .optional();
    var response = CheckLiveBookingResponse.newBuilder().setLive(booking.isPresent());
    booking.ifPresent(b -> response.setBookingId(b.toString()));
    out.onNext(response.build());
    out.onCompleted();
  }
}
