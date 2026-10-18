package care.solace.tracking.modules;

import care.solace.contracts.core.v1.GetAgeRequest;
import care.solace.contracts.core.v1.ProfileServiceGrpc.ProfileServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The seeker's age from Core, read when they enrol in a module with a minimum age (Tracking Service
 * Spec, Eligibility). 2-second deadline and a breaker; on any failure, enrolment in an age-limited
 * module is refused and the seeker can retry.
 */
@Component
public class AgeCheck {

  private final ProfileServiceBlockingStub stub;
  private final ResilientCalls calls;

  AgeCheck(ProfileServiceBlockingStub stub, ResilientCalls calls) {
    this.stub = stub;
    this.calls = calls;
  }

  public Optional<Integer> years(UUID seeker) {
    return calls.call(
        "core",
        () ->
            Optional.of(
                stub.getAge(
                        GetAgeRequest.newBuilder().setSeekerSubjectId(seeker.toString()).build())
                    .getAgeYears()),
        failure -> Optional.empty());
  }
}
