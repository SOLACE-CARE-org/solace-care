package care.solace.core.system;

import care.solace.contracts.platform.v1.PingServiceGrpc;
import care.solace.contracts.platform.v1.PingServiceGrpc.PingServiceBlockingStub;
import care.solace.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.kafka.core.KafkaAdmin;

/** gRPC stubs for the ping, and the {@code core.system} topic Core produces to. */
@Configuration(proxyBeanMethods = false)
class SystemConfiguration {

  @Bean
  PingServiceBlockingStub trackingPing(GrpcChannelFactory channels) {
    return PingServiceGrpc.newBlockingStub(channels.createChannel("tracking"));
  }

  @Bean
  PingServiceBlockingStub aiPing(GrpcChannelFactory channels) {
    return PingServiceGrpc.newBlockingStub(channels.createChannel("ai"));
  }

  /** Partition and replica counts are settings: not in the specs, and they differ per cluster. */
  @Bean
  KafkaAdmin.NewTopics systemTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter("core.system", partitions, replicas).toArray(NewTopic[]::new));
  }
}
