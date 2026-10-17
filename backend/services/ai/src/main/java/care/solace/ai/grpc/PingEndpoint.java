package care.solace.ai.grpc;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingResponse;
import care.solace.contracts.platform.v1.PingServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/** Answers the Phase 0 end-to-end ping from Core. Carries no user data. */
@Service
class PingEndpoint extends PingServiceGrpc.PingServiceImplBase {

  private final String version;

  PingEndpoint(ObjectProvider<BuildProperties> build) {
    BuildProperties properties = build.getIfAvailable();
    this.version = properties == null ? "dev" : properties.getVersion();
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responses) {
    responses.onNext(PingResponse.newBuilder().setService("ai").setVersion(version).build());
    responses.onCompleted();
  }
}
