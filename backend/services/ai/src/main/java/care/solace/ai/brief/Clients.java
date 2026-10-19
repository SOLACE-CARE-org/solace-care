package care.solace.ai.brief;

import care.solace.contracts.core.v1.BriefSourceServiceGrpc;
import care.solace.contracts.tracking.v1.TrendSummaryServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Stubs for the brief's sources (brief_source_service.proto, trend_summary_service.proto). */
@Configuration(proxyBeanMethods = false)
class Clients {

  @Bean
  BriefSourceServiceGrpc.BriefSourceServiceBlockingStub coreBriefSources(
      GrpcChannelFactory channels) {
    return BriefSourceServiceGrpc.newBlockingStub(channels.createChannel("core"));
  }

  @Bean
  TrendSummaryServiceGrpc.TrendSummaryServiceBlockingStub trackingTrends(
      GrpcChannelFactory channels) {
    return TrendSummaryServiceGrpc.newBlockingStub(channels.createChannel("tracking"));
  }
}
