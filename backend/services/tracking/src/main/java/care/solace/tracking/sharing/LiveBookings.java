package care.solace.tracking.sharing;

import care.solace.contracts.core.v1.BookingAccessServiceGrpc.BookingAccessServiceBlockingStub;
import care.solace.contracts.core.v1.CheckLiveBookingRequest;
import care.solace.grpc.ResilientCalls;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Asks Core on every specialist read whether a booking between the two is live (Tracking Service
 * Spec, Specialist reads). Never cached; 2-second deadline; any error or timeout denies.
 */
@Component
public class LiveBookings {

  private final BookingAccessServiceBlockingStub stub;
  private final ResilientCalls calls;

  LiveBookings(BookingAccessServiceBlockingStub stub, ResilientCalls calls) {
    this.stub = stub;
    this.calls = calls;
  }

  public boolean live(UUID seeker, UUID specialist) {
    return calls.call(
        "core",
        () ->
            stub.checkLiveBooking(
                    CheckLiveBookingRequest.newBuilder()
                        .setSeekerSubjectId(seeker.toString())
                        .setSpecialistSubjectId(specialist.toString())
                        .build())
                .getLive(),
        failure -> false);
  }
}
