package care.solace.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingResponse;
import care.solace.contracts.platform.v1.PingServiceGrpc;
import care.solace.contracts.tracking.v1.GetTrendSummaryRequest;
import care.solace.contracts.tracking.v1.GetTrendSummaryResponse;
import care.solace.contracts.tracking.v1.TrendSummaryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest(
    classes = GrpcSecurityTest.App.class,
    properties = {
      "spring.grpc.server.inprocess.name=grpc-security-test",
      "spring.grpc.server.port=0",
      "spring.grpc.client.inprocess.enabled=false",
      "solace.security.audience=tracking"
    })
class GrpcSecurityTest {

  private final ManagedChannel channel =
      InProcessChannelBuilder.forName("grpc-security-test").build();

  @AfterEach
  void close() {
    channel.shutdownNow();
  }

  @Test
  void callWithTheDeclaredScopeIsServedAsTheCallingService() {
    PingResponse response =
        pingStub("token-with-ping-scope").ping(PingRequest.getDefaultInstance());

    assertThat(response.getService()).isEqualTo("caller=core-service");
  }

  @Test
  void callWithAnotherScopeIsRefused() {
    assertThatThrownBy(
            () -> pingStub("token-with-other-scope").ping(PingRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  @Test
  void callWithoutATokenIsUnauthenticated() {
    assertThatThrownBy(
            () -> PingServiceGrpc.newBlockingStub(channel).ping(PingRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  @Test
  void invalidTokenIsUnauthenticated() {
    assertThatThrownBy(() -> pingStub("forged").ping(PingRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  @Test
  void methodsWithoutADeclaredScopeAreRefusedEvenWithAValidToken() {
    Metadata headers = bearer("token-with-ping-scope");
    TrendSummaryServiceGrpc.TrendSummaryServiceBlockingStub stub =
        TrendSummaryServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    assertThatThrownBy(() -> stub.getTrendSummary(GetTrendSummaryRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  private PingServiceGrpc.PingServiceBlockingStub pingStub(String token) {
    return PingServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(bearer(token)));
  }

  private static Metadata bearer(String token) {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    return headers;
  }

  @Configuration
  @EnableAutoConfiguration
  static class App {

    @Bean
    GrpcMethodScopes grpcMethodScopes() {
      return new GrpcMethodScopes(Map.of("solace.platform.v1.PingService/Ping", "tracking:ping"));
    }

    /** Stands in for Keycloak: the token text says which scope the caller has. */
    @Bean
    JwtDecoder solaceServiceJwtDecoder() {
      return token -> {
        String scope =
            switch (token) {
              case "token-with-ping-scope" -> "tracking:ping";
              case "token-with-other-scope" -> "tracking:summary.read";
              default -> throw new BadJwtException("bad token");
            };
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("service-account")
            .claim("client_id", "core-service")
            .claim("scope", scope)
            .claim("aud", List.of("tracking"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }

    @Bean
    PingServiceGrpc.PingServiceImplBase ping() {
      return new PingServiceGrpc.PingServiceImplBase() {
        @Override
        public void ping(PingRequest request, StreamObserver<PingResponse> responses) {
          Jwt caller = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
          responses.onNext(
              PingResponse.newBuilder()
                  .setService("caller=" + caller.getClaimAsString("client_id"))
                  .build());
          responses.onCompleted();
        }
      };
    }

    @Bean
    TrendSummaryServiceGrpc.TrendSummaryServiceImplBase trends() {
      return new TrendSummaryServiceGrpc.TrendSummaryServiceImplBase() {
        @Override
        public void getTrendSummary(
            GetTrendSummaryRequest request, StreamObserver<GetTrendSummaryResponse> responses) {
          responses.onNext(GetTrendSummaryResponse.getDefaultInstance());
          responses.onCompleted();
        }
      };
    }
  }
}
