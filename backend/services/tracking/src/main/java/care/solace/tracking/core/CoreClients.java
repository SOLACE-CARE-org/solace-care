package care.solace.tracking.core;

import care.solace.contracts.ai.v1.InsightServiceGrpc;
import care.solace.contracts.core.v1.BookingAccessServiceGrpc;
import care.solace.contracts.core.v1.ProfileServiceGrpc;
import care.solace.contracts.core.v1.TaxonomyServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Stubs for Core's gRPC services (contracts/proto/solace/core/v1). */
@Configuration(proxyBeanMethods = false)
class CoreClients {

  @Bean
  ProfileServiceGrpc.ProfileServiceBlockingStub coreProfile(GrpcChannelFactory channels) {
    return ProfileServiceGrpc.newBlockingStub(channels.createChannel("core"));
  }

  @Bean
  TaxonomyServiceGrpc.TaxonomyServiceBlockingStub coreTaxonomy(GrpcChannelFactory channels) {
    return TaxonomyServiceGrpc.newBlockingStub(channels.createChannel("core"));
  }

  @Bean
  BookingAccessServiceGrpc.BookingAccessServiceBlockingStub coreBookingAccess(
      GrpcChannelFactory channels) {
    return BookingAccessServiceGrpc.newBlockingStub(channels.createChannel("core"));
  }

  /** The AI service, for recommendation wording (Phase 3, D56). */
  @Bean
  InsightServiceGrpc.InsightServiceBlockingStub aiInsights(GrpcChannelFactory channels) {
    return InsightServiceGrpc.newBlockingStub(channels.createChannel("ai"));
  }
}
