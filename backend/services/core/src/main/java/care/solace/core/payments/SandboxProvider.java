package care.solace.core.payments;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in aggregator for local runs and tests, never for real money. A charge is approved {@code
 * approve-after} after it was asked for, unless the phone ends in 99; a transfer succeeds at once,
 * unless the phone ends in 98. Webhooks use Notch Pay's format with the sandbox secret.
 */
class SandboxProvider implements PaymentProvider {

  private final PaymentSettings.Sandbox settings;
  private final Clock clock;
  private final Map<String, Entry> charges = new ConcurrentHashMap<>();
  private final Map<String, Entry> transfers = new ConcurrentHashMap<>();

  SandboxProvider(PaymentSettings.Sandbox settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
  }

  private record Entry(Instant at, String phone) {}

  @Override
  public String name() {
    return "sandbox";
  }

  @Override
  public Started charge(ChargeRequest request) {
    String ref = "sbx_c_" + UUID.randomUUID();
    charges.put(ref, new Entry(clock.instant(), request.phone()));
    return new Started(ref, Status.PENDING);
  }

  @Override
  public Outcome chargeStatus(String providerRef) {
    Entry e = charges.get(providerRef);
    if (e == null) {
      return new Outcome(Status.FAILED, "unknown");
    }
    if (e.phone().endsWith("99")) {
      return new Outcome(Status.FAILED, "declined");
    }
    return clock.instant().isBefore(e.at().plus(settings.approveAfter()))
        ? new Outcome(Status.PENDING, null)
        : new Outcome(Status.COMPLETE, null);
  }

  @Override
  public Started transfer(TransferRequest request) {
    String ref = "sbx_t_" + UUID.randomUUID();
    transfers.put(ref, new Entry(clock.instant(), request.phone()));
    return new Started(ref, Status.PENDING);
  }

  @Override
  public Outcome transferStatus(String providerRef) {
    Entry e = transfers.get(providerRef);
    if (e == null) {
      return new Outcome(Status.FAILED, "unknown");
    }
    return e.phone().endsWith("98")
        ? new Outcome(Status.FAILED, "wallet unavailable")
        : new Outcome(Status.COMPLETE, null);
  }

  @Override
  public boolean verify(String signature, byte[] body) {
    return NotchPayFormat.verify(settings.webhookSecret(), signature, body);
  }

  @Override
  public Optional<Notification> parse(byte[] body) {
    return NotchPayFormat.parse(body);
  }
}
