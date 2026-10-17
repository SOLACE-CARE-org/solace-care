package care.solace.core.system;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingServiceGrpc.PingServiceBlockingStub;
import care.solace.events.Events;
import care.solace.grpc.ResilientCalls;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Phase 0 end-to-end slice (implementation-plan.md, Phase 0 gate): Core calls Tracking and AI
 * over gRPC, then publishes {@code core.system.pinged} through the outbox. One trace covers the
 * gateway, Core, both gRPC calls, the Kafka send and Tracking's consumer.
 */
@Service
class Pinger {

  private final PingServiceBlockingStub tracking;
  private final PingServiceBlockingStub ai;
  private final ResilientCalls calls;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;

  Pinger(
      @Qualifier("trackingPing") PingServiceBlockingStub tracking,
      @Qualifier("aiPing") PingServiceBlockingStub ai,
      ResilientCalls calls,
      ApplicationEventPublisher events,
      TransactionTemplate transactions) {
    this.tracking = tracking;
    this.ai = ai;
    this.calls = calls;
    this.events = events;
    this.transactions = transactions;
  }

  PingResult ping() {
    // Remote calls first, outside any database transaction.
    boolean trackingUp = reachable("tracking", tracking);
    boolean aiUp = reachable("ai", ai);
    transactions.executeWithoutResult(
        status ->
            events.publishEvent(
                Events.platformEnvelope(
                    SystemPinged.TYPE, SystemPinged.VERSION, new SystemPinged(trackingUp, aiUp))));
    return new PingResult(true, trackingUp, aiUp);
  }

  private boolean reachable(String callee, PingServiceBlockingStub stub) {
    return calls.call(
        callee,
        () -> !stub.ping(PingRequest.getDefaultInstance()).getService().isEmpty(),
        failure -> false);
  }
}
