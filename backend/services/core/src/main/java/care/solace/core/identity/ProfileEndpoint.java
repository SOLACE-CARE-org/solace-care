package care.solace.core.identity;

import care.solace.contracts.core.v1.GetAgeRequest;
import care.solace.contracts.core.v1.GetAgeResponse;
import care.solace.contracts.core.v1.ProfileServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** A seeker's age in whole years for module eligibility; never the date of birth. */
@Service
class ProfileEndpoint extends ProfileServiceGrpc.ProfileServiceImplBase {

  private static final ZoneId CAMEROON = ZoneId.of("Africa/Douala");

  private final JdbcClient db;
  private final Clock clock;

  ProfileEndpoint(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Override
  public void getAge(GetAgeRequest request, StreamObserver<GetAgeResponse> out) {
    UUID seeker;
    try {
      seeker = UUID.fromString(request.getSeekerSubjectId());
    } catch (IllegalArgumentException e) {
      out.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    var born =
        db.sql("SELECT date_of_birth FROM seeker_profile WHERE account_id = :a")
            .param("a", seeker)
            .query((rs, n) -> rs.getDate("date_of_birth").toLocalDate())
            .optional();
    if (born.isEmpty()) {
      out.onError(Status.NOT_FOUND.asRuntimeException());
      return;
    }
    int years = Period.between(born.get(), LocalDate.now(clock.withZone(CAMEROON))).getYears();
    out.onNext(GetAgeResponse.newBuilder().setAgeYears(years).build());
    out.onCompleted();
  }
}
